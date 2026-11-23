package dev.s200.cozmo.core;

/**
 * Parametric procedural face geometry inspired by PyCozmo's ProceduralFace.
 * Separate left and right EyeParams with corner radius, rotation, and curved upper/lower eyelids.
 * Pure Java 11, zero Android dependencies.
 */
public final class FaceParams {
    public static final class EyeParams {
        public final float cx, cy;
        public final float width, height;
        public final float angleRad;
        public final float cornerRadius;
        public final float upperLidY, upperLidAngle, upperLidBend;
        public final float lowerLidY, lowerLidAngle, lowerLidBend;

        public EyeParams(float cx, float cy, float width, float height, float angleRad, float cornerRadius,
                         float upperLidY, float upperLidAngle, float upperLidBend,
                         float lowerLidY, float lowerLidAngle, float lowerLidBend) {
            this.cx = cx;
            this.cy = cy;
            this.width = width;
            this.height = height;
            this.angleRad = angleRad;
            this.cornerRadius = cornerRadius;
            this.upperLidY = upperLidY;
            this.upperLidAngle = upperLidAngle;
            this.upperLidBend = upperLidBend;
            this.lowerLidY = lowerLidY;
            this.lowerLidAngle = lowerLidAngle;
            this.lowerLidBend = lowerLidBend;
        }

        public static EyeParams lerp(EyeParams a, EyeParams b, float u) {
            return new EyeParams(
                mix(a.cx, b.cx, u),
                mix(a.cy, b.cy, u),
                mix(a.width, b.width, u),
                mix(a.height, b.height, u),
                mix(a.angleRad, b.angleRad, u),
                mix(a.cornerRadius, b.cornerRadius, u),
                mix(a.upperLidY, b.upperLidY, u),
                mix(a.upperLidAngle, b.upperLidAngle, u),
                mix(a.upperLidBend, b.upperLidBend, u),
                mix(a.lowerLidY, b.lowerLidY, u),
                mix(a.lowerLidAngle, b.lowerLidAngle, u),
                mix(a.lowerLidBend, b.lowerLidBend, u)
            );
        }

        public EyeParams simplifiedLevel1() {
            return new EyeParams(cx, cy, width, height, 0f, Math.min(3.0f, cornerRadius),
                upperLidY, 0f, 0f, lowerLidY, 0f, 0f);
        }

        public EyeParams simplifiedLevel2() {
            return new EyeParams(Math.round(cx), Math.round(cy), Math.round(width), Math.round(height),
                0f, 2.0f, 0f, 0f, 0f, 0f, 0f, 0f);
        }
    }

    public final EyeParams left;
    public final EyeParams right;
    public final float gazeX;
    public final float gazeY;
    public final float blinkOpenness;

    public FaceParams(EyeParams left, EyeParams right, float gazeX, float gazeY, float blinkOpenness) {
        this.left = left;
        this.right = right;
        this.gazeX = gazeX;
        this.gazeY = gazeY;
        this.blinkOpenness = Math.max(0.05f, Math.min(1.0f, blinkOpenness));
    }

    public FaceParams withGazeAndBlink(float gx, float gy, float blink) {
        return new FaceParams(left, right, gx, gy, blink);
    }

    public FaceParams simplifiedLevel1() {
        return new FaceParams(left.simplifiedLevel1(), right.simplifiedLevel1(), gazeX, gazeY, blinkOpenness);
    }

    public FaceParams simplifiedLevel2() {
        return new FaceParams(left.simplifiedLevel2(), right.simplifiedLevel2(), Math.round(gazeX), Math.round(gazeY), blinkOpenness);
    }

    /**
     * Smoothstep cubic ease-in-out interpolation between two FaceParams states.
     */
    public static FaceParams lerp(FaceParams a, FaceParams b, float t) {
        if (a == null) return b;
        if (b == null) return a;
        float clamped = Math.max(0f, Math.min(1f, t));
        float u = clamped * clamped * (3f - 2f * clamped);
        return new FaceParams(
            EyeParams.lerp(a.left, b.left, u),
            EyeParams.lerp(a.right, b.right, u),
            mix(a.gazeX, b.gazeX, u),
            mix(a.gazeY, b.gazeY, u),
            mix(a.blinkOpenness, b.blinkOpenness, u)
        );
    }

    public static FaceParams forExpression(Personality.Expression expr, Personality.Profile profile) {
        Personality.Expression e = expr == null ? Personality.Expression.NEUTRAL : expr;
        Personality.Profile p = profile == null ? Personality.Profile.CURIOSO : profile;

        float w = 33f, h = 20.4f;
        float r = 4.2f;
        float lUpY = 0f, rUpY = 0f, lUpAng = 0f, rUpAng = 0f, lUpBend = 0f, rUpBend = 0f;
        float lLowY = 0f, rLowY = 0f, lLowAng = 0f, rLowAng = 0f, lLowBend = 0f, rLowBend = 0f;
        float lH = h, rH = h, lW = w, rW = w;
        float cy = 15.5f;

        switch (e) {
            case HAPPY:
                lH = h * 0.96f; rH = h * 0.96f;
                lLowY = 0.34f; rLowY = 0.34f;
                lLowBend = 0.42f; rLowBend = 0.42f;
                break;
            case EXCITED:
                lW = w * 1.10f; rW = w * 1.10f;
                lH = h * 1.14f; rH = h * 1.14f;
                r = 5.0f;
                lLowY = 0.28f; rLowY = 0.28f;
                lLowBend = 0.38f; rLowBend = 0.38f;
                break;
            case CURIOUS:
                lW = w * 1.04f; rW = w * 1.08f;
                lH = h * 0.96f; rH = h * 1.14f;
                lUpY = 0.24f; rUpY = 0.0f;
                lUpAng = -0.09f; rUpAng = 0.11f;
                break;
            case SURPRISED:
                lW = w * 1.14f; rW = w * 1.14f;
                lH = h * 1.22f; rH = h * 1.22f;
                r = 5.6f;
                break;
            case SKEPTICAL:
                lUpY = 0.40f; rUpY = 0.06f;
                lUpAng = 0.17f; rUpAng = -0.12f;
                lLowY = 0.16f;
                break;
            case SAD:
                lH = h * 0.90f; rH = h * 0.90f;
                lUpY = 0.36f; rUpY = 0.36f;
                lUpAng = -0.22f; rUpAng = 0.22f;
                lUpBend = -0.15f; rUpBend = -0.15f;
                cy = 17.3f;
                break;
            case SLEEPY:
                lH = h * 0.75f; rH = h * 0.75f;
                lUpY = 0.54f; rUpY = 0.54f;
                lLowY = 0.20f; rLowY = 0.20f;
                cy = 17.8f;
                break;
            case FOCUSED:
                lUpY = 0.30f; rUpY = 0.30f;
                lLowY = 0.24f; rLowY = 0.24f;
                lUpAng = 0.18f; rUpAng = -0.18f;
                break;
            case NEUTRAL:
            default:
                break;
        }

        // Subtle profile signature
        if (p == Personality.Profile.BIRICHINO) {
            lUpAng += 0.04f;
            rH = Math.min(25f, rH * 1.04f);
        } else if (p == Personality.Profile.TRANQUILLO) {
            lUpY = Math.min(0.65f, lUpY + 0.06f);
            rUpY = Math.min(0.65f, rUpY + 0.06f);
        } else if (p == Personality.Profile.CURIOSO) {
            lH = Math.min(25f, lH * 1.03f);
            rH = Math.min(25f, rH * 1.03f);
        } else if (p == Personality.Profile.AFFETTUOSO) {
            lLowBend = Math.max(lLowBend, 0.14f);
            rLowBend = Math.max(rLowBend, 0.14f);
            lLowY = Math.max(lLowY, 0.08f);
            rLowY = Math.max(rLowY, 0.08f);
        }

        EyeParams left = new EyeParams(39f, cy, lW, lH, 0f, r, lUpY, lUpAng, lUpBend, lLowY, lLowAng, lLowBend);
        EyeParams right = new EyeParams(89f, cy, rW, rH, 0f, r, rUpY, rUpAng, rUpBend, rLowY, rLowAng, rLowBend);
        return new FaceParams(left, right, 0f, 0f, 1.0f);
    }

    private static float mix(float a, float b, float u) {
        return a + (b - a) * u;
    }
}
