package dev.s200.cozmo.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;

/** Factory recovery using PyCozmo's FirmwareUpdate / FirmwareUpdateResult messages. */
final class FirmwareRecovery {
    static final int IMAGE_SIZE = 382416;
    private static final String IMAGE_SHA256 =
        "a4e266cfaaa95e1895fb48729603a732c24f4808a19e14677792137d63a449c0";
    private static final int CHUNK_SIZE = 1024;
    private static final int CHUNKS = (IMAGE_SIZE + CHUNK_SIZE - 1) / CHUNK_SIZE;
    private static final int COMMIT = 0xffff;
    private static final int MAX_UNCONFIRMED_CHUNKS = 8;

    private final byte[] image;
    private int nextChunk;
    private int confirmed = -1;
    private int lastReported = -1;
    private long lastSend;
    private long lastConfirmedAt;
    private String lastRxSummary = "";
    private boolean committing;
    private volatile String outcome;

    FirmwareRecovery(byte[] image) {
        if (image.length != IMAGE_SIZE || !IMAGE_SHA256.equals(sha256(image))) {
            throw new IllegalArgumentException("Immagine firmware diversa dalla build 2381 verificata");
        }
        this.image = image.clone();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(Character.forDigit((value & 255) >>> 4, 16))
                .append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    boolean active() { return outcome == null; }
    String outcome() { return outcome; }
    boolean committing() { return committing; }
    int confirmed() { return confirmed + 1; }

    void advance(long now, boolean transportDrained, Consumer<Wire.Packet> send) {
        if (!active()) return;
        if (nextChunk == 0) {
            send.accept(dataPacket(nextChunk++));
            lastSend = now;
            lastConfirmedAt = now;
            return;
        }

        long elapsed = now - lastSend;
        if (committing) {
            if (elapsed >= 30_000) outcome = "Conferma finale assente dopo l'invio di chiusura";
            return;
        }
        if (nextChunk == 1 && confirmed < 0) {
            if (elapsed >= 35_000) outcome = "Il bootloader non ha confermato il primo blocco";
            return;
        }

        if (!transportDrained) {
            if (elapsed >= 15_000) outcome = "Trasporto UDP bloccato al blocco " + (nextChunk - 1);
            return;
        }
        if (nextChunk == CHUNKS) {
            boolean readyToCommit = confirmed >= CHUNKS - 1
                || (confirmed >= CHUNKS - 2 && elapsed >= 600)
                || (elapsed >= 1500 && (CHUNKS - 1 - confirmed) <= MAX_UNCONFIRMED_CHUNKS);
            if (!readyToCommit) {
                if (now - lastConfirmedAt >= 20_000) {
                    outcome = "Conferma finale prima del commit assente (conf=" + confirmed + "/" + (CHUNKS - 1) + ")";
                }
                return;
            }
            send.accept(Wire.command(0xaf, Wire.le(2 + CHUNK_SIZE).putShort((short) COMMIT).array()));
            committing = true;
            lastSend = now;
            return;
        }

        int trailing = (nextChunk - 1) - confirmed;
        boolean pairConfirmed = trailing <= 0;
        boolean pairedReport = trailing == 1 && elapsed >= 500;
        boolean missedReportWindow = trailing <= MAX_UNCONFIRMED_CHUNKS && elapsed >= 650 && (now - lastConfirmedAt) < 15_000;
        if (!pairConfirmed && !pairedReport && !missedReportWindow) {
            if (elapsed >= 15_000 || (now - lastConfirmedAt) >= 20_000) {
                outcome = "Conferma del blocco " + (nextChunk - 1) + " assente (conf=" + confirmed
                    + (lastRxSummary.isEmpty() ? "" : ", " + lastRxSummary) + ")";
            }
            return;
        }
        int limit = Math.min(CHUNKS, nextChunk + 2);
        while (nextChunk < limit) send.accept(dataPacket(nextChunk++));
        lastSend = now;
    }

    private Wire.Packet dataPacket(int index) {
        int offset = index * CHUNK_SIZE;
        ByteBuffer data = Wire.le(2 + CHUNK_SIZE).putShort((short) index);
        data.put(image, offset, Math.min(CHUNK_SIZE, image.length - offset));
        return Wire.command(0xaf, data.array());
    }

    String receive(long now, byte[] payload) {
        if (!active()) return null;
        if (payload.length != 7) {
            lastRxSummary = "len=" + payload.length;
            return "Risposta bootloader anomala · len=" + payload.length;
        }
        ByteBuffer data = Wire.read(payload);
        long bytes = Integer.toUnsignedLong(data.getInt()); // Byte count can include the zero padding of the last block.
        int index = data.getShort() & 65535;
        int status = data.get() & 255;
        lastRxSummary = "bytes=" + bytes + " idx=" + index + " st=" + status;
        if (status == 10 && committing) {
            outcome = "Firmware ricevuto · bootloader ha confermato il completamento";
            return outcome;
        } else if (status != 0) {
            outcome = "Bootloader ha rifiutato il blocco " + index + " · codice " + status + " (bytes=" + bytes + ")";
            return outcome;
        } else if (index < nextChunk && index >= confirmed) {
            confirmed = index;
            lastConfirmedAt = now;
        } else {
            return "0xef fuori sequenza: " + lastRxSummary + " next=" + nextChunk + " conf=" + confirmed;
        }
        if (confirmed >= 0 && (confirmed / 32 != lastReported || confirmed >= 254)) {
            lastReported = confirmed / 32;
            return "Blocchi confermati: " + confirmed() + " (" + lastRxSummary + ")";
        }
        return null;
    }
}
