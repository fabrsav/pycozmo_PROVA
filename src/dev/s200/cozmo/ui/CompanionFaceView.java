package dev.s200.cozmo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import dev.s200.cozmo.core.FaceParams;
import dev.s200.cozmo.core.FaceRenderer;
import dev.s200.cozmo.core.Personality;

/** Front three-quarter portrait. The eyes use the same geometry as the robot's OLED. */
final class CompanionFaceView extends View {
    private static final int WIDTH = 128, HEIGHT = 32;
    private static final int MINT = 0xFF8EFFD7;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Bitmap eyes = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private final int[] colors = new int[WIDTH * HEIGHT];
    private final boolean[] pixels = new boolean[WIDTH * HEIGHT];
    private final RectF screen = new RectF(2, 9, 62, 36);
    private final Shader enamel = new LinearGradient(0, 24, 0, 88, 0xFFE9F0EB, 0xFF8FAFB1, Shader.TileMode.CLAMP);
    private Personality.Expression expression;
    private Personality.Profile profile;

    CompanionFaceView(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        show(Personality.Expression.NEUTRAL, Personality.Profile.CURIOSO);
    }

    void show(Personality.Expression next, Personality.Profile character) {
        if (expression == next && profile == character) return;
        expression = next;
        profile = character;
        FaceRenderer.renderInto(FaceParams.forExpression(next, character), pixels);
        for (int i = 0; i < colors.length; i++) colors[i] = pixels[i] ? MINT : 0;
        eyes.setPixels(colors, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 174f, getHeight() / 138f);
        canvas.save();
        canvas.translate((getWidth() - 174 * scale) / 2, (getHeight() - 138 * scale) / 2);
        canvas.scale(scale, scale);

        ink(0x50365556);
        canvas.drawOval(8, 109, 167, 131, paint);
        ink(0x60101E28);
        canvas.drawOval(24, 110, 151, 126, paint);

        // Rear tread and chassis, visible behind the head and fork.
        round(canvas, 31, 88, 101, 118, 14, 0xFF182F3B);
        polygon(canvas, 0xFF8CAAA9, 43, 83, 95, 72, 147, 81, 154, 96, 97, 109, 46, 104);
        polygon(canvas, 0xFFD1DFD9, 40, 84, 90, 71, 139, 80, 90, 94);
        polygon(canvas, 0xFFEB8757, 95, 77, 114, 73, 131, 77, 112, 81);
        polygon(canvas, 0xFF537276, 58, 82, 75, 79, 92, 85, 75, 88);

        // Near tread has a sloped rear and three recessed sprockets.
        shape.rewind();
        shape.moveTo(80, 93);
        shape.lineTo(132, 83);
        shape.cubicTo(143, 81, 147, 85, 151, 92);
        shape.lineTo(157, 100);
        shape.cubicTo(162, 108, 156, 115, 148, 117);
        shape.lineTo(86, 127);
        shape.cubicTo(62, 130, 58, 99, 80, 93);
        shape.close();
        ink(0xFF142832);
        canvas.drawPath(shape, paint);
        polygon(canvas, 0xFF34515A, 78, 99, 137, 88, 153, 106, 148, 111, 82, 122);
        wheel(canvas, 82, 109, 13);
        wheel(canvas, 114, 104, 9);
        wheel(canvas, 142, 100, 11);
        stroke(canvas, 0xFF769595, 1.2f, 97, 119, 147, 111);

        // Articulated lift arms and front lip.
        polygon(canvas, 0xFFB26548, 29, 72, 36, 74, 31, 110, 21, 112);
        polygon(canvas, 0xFFFAB58A, 75, 75, 84, 78, 76, 114, 64, 117);
        polygon(canvas, 0xFFCC7655, 81, 78, 85, 81, 78, 117, 72, 120);
        polygon(canvas, 0xFFDADFD3, 13, 108, 65, 113, 76, 110, 75, 117, 62, 121, 13, 115);
        polygon(canvas, 0xFFF6F1DE, 12, 106, 60, 110, 74, 107, 75, 111, 62, 115, 12, 111);
        wheelPin(canvas, 79, 79);

        // Head casing, side hinge, smoked visor and camera.
        shape.rewind();
        shape.moveTo(43, 27);
        shape.lineTo(99, 20);
        shape.quadTo(108, 18, 115, 23);
        shape.lineTo(132, 35);
        shape.quadTo(136, 38, 135, 46);
        shape.lineTo(132, 64);
        shape.quadTo(131, 71, 122, 75);
        shape.lineTo(99, 84);
        shape.lineTo(43, 87);
        shape.quadTo(32, 87, 31, 76);
        shape.lineTo(30, 43);
        shape.quadTo(30, 30, 43, 27);
        shape.close();
        ink(0xFFE1E7DD);
        paint.setShader(enamel);
        canvas.drawPath(shape, paint);
        paint.setShader(null);
        polygon(canvas, 0xFF719292, 109, 28, 129, 38, 131, 46, 127, 67, 107, 77);
        ink(0xFF45676E);
        canvas.drawOval(113, 39, 126, 64, paint);
        ink(0xFFABC3C0);
        canvas.drawOval(115, 44, 123, 59, paint);
        stroke(canvas, 0xFFD1DECF, 1.4f, 116, 51, 122, 50);
        canvas.save();
        canvas.translate(36, 35);
        canvas.skew(0.035f, -0.112f);
        round(canvas, -1, -1, 69, 47, 10, 0xFF69888B);
        round(canvas, 0, 0, 67, 45, 9, 0xFF091A23);
        round(canvas, 5, 4, 61, 41, 7, 0xFF0A2027);
        ink(0xFF496367);
        canvas.drawCircle(33, 6, 2.2f, paint);
        ink(0xFFADCFC8);
        canvas.drawCircle(33.5f, 5.6f, 0.75f, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setFilterBitmap(false);
        canvas.drawBitmap(eyes, null, screen, paint);
        canvas.restore();
        canvas.restore();
    }

    private void wheel(Canvas c, float x, float y, float r) {
        ink(0xFF0D222C);
        c.drawCircle(x, y, r, paint);
        ink(0xFFAC6B4F);
        c.drawCircle(x, y, r * 0.8f, paint);
        ink(0xFFF8AE7F);
        c.drawCircle(x - 0.7f, y - 0.7f, r * 0.62f, paint);
        ink(0xFF5E6262);
        c.drawCircle(x, y, r * 0.35f, paint);
        ink(0xFF243C44);
        c.drawCircle(x, y, r * 0.17f, paint);
    }

    private void wheelPin(Canvas c, float x, float y) {
        ink(0xFF5E6C6C);
        c.drawCircle(x, y, 3, paint);
        ink(0xFFCDDDD5);
        c.drawCircle(x, y, 1.3f, paint);
    }

    private void polygon(Canvas c, int color, float... vertices) {
        shape.rewind();
        shape.moveTo(vertices[0], vertices[1]);
        for (int i = 2; i < vertices.length; i += 2) shape.lineTo(vertices[i], vertices[i + 1]);
        shape.close();
        ink(color);
        c.drawPath(shape, paint);
    }

    private void stroke(Canvas c, int color, float width, float x, float y, float x2, float y2) {
        ink(color);
        paint.setStrokeWidth(width);
        c.drawLine(x, y, x2, y2, paint);
    }

    private void round(Canvas c, float x1, float y1, float x2, float y2, float radius, int color) {
        ink(color);
        c.drawRoundRect(x1, y1, x2, y2, radius, radius, paint);
    }

    private void ink(int color) {
        paint.setColor(color);
    }
}
