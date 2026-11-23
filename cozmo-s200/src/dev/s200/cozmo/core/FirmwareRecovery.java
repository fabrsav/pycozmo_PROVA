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

    private final byte[] image;
    private int nextChunk;
    private int confirmed = -1;
    private int lastReported = -1;
    private long lastSend;
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
            return;
        }

        long elapsed = now - lastSend;
        if (committing) {
            if (elapsed >= 30_000) outcome = "Conferma finale assente dopo l'invio di chiusura";
            return;
        }
        if (nextChunk == 1 && confirmed < 0) {
            if (elapsed >= 30_000) outcome = "Il bootloader non ha confermato il primo blocco";
            return;
        }

        // Some Cozmo builds report only every second block. Require the reliable
        // transport to drain and the flash report to trail by at most one block.
        boolean pairConfirmed = confirmed >= nextChunk - 1;
        boolean pairedReport = confirmed >= nextChunk - 2 && elapsed >= 650;
        if (!transportDrained || (!pairConfirmed && !pairedReport)) {
            if (elapsed >= 9_000) outcome = "Conferma del blocco " + (nextChunk - 1) + " assente";
            return;
        }
        if (nextChunk == CHUNKS) {
            send.accept(Wire.command(0xaf, Wire.le(2 + CHUNK_SIZE).putShort((short) COMMIT).array()));
            committing = true;
        } else {
            int limit = Math.min(CHUNKS, nextChunk + 2);
            while (nextChunk < limit) send.accept(dataPacket(nextChunk++));
        }
        lastSend = now;
    }

    private Wire.Packet dataPacket(int index) {
        int offset = index * CHUNK_SIZE;
        ByteBuffer data = Wire.le(2 + CHUNK_SIZE).putShort((short) index);
        data.put(image, offset, Math.min(CHUNK_SIZE, image.length - offset));
        return Wire.command(0xaf, data.array());
    }

    boolean receive(byte[] payload) {
        if (!active() || payload.length != 7) return false;
        ByteBuffer data = Wire.read(payload);
        data.getInt(); // Byte count can include the zero padding of the last block.
        int index = data.getShort() & 65535;
        int status = data.get() & 255;
        if (status == 10 && committing) {
            outcome = "Firmware ricevuto · bootloader ha confermato il completamento";
        } else if (status != 0) {
            outcome = "Bootloader ha rifiutato il blocco " + index + " · codice " + status;
        } else if (index < nextChunk && index >= confirmed) {
            confirmed = index;
        }
        if (outcome != null || (confirmed >= 0 && confirmed / 32 != lastReported)) {
            lastReported = confirmed / 32;
            return true;
        }
        return false;
    }
}
