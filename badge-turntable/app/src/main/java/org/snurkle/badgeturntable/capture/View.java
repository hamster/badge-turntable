package org.snurkle.badgeturntable.capture;

/** The four turntable views. 0° is the badge front as framed when Capture is pressed. */
public enum View {
    FRONT(0, "front", "Front"),
    SIDE_090(90, "side-090", "Side (90°)"),
    BACK(180, "back", "Back"),
    SIDE_270(270, "side-270", "Side (270°)");

    public final int angle;
    public final String baseName;
    public final String caption;

    View(int angle, String baseName, String caption) {
        this.angle = angle;
        this.baseName = baseName;
        this.caption = caption;
    }

    /** "front.jpg", or "with-saos-front.jpg" for an extra set. */
    public String filename(String setPrefix) {
        return setPrefix + baseName + ".jpg";
    }

    /** "Front", or "With SAOs installed (front)" for an extra set. */
    public String caption(String setLabel) {
        if (setLabel == null || setLabel.isEmpty()) return caption;
        return setLabel + " (" + caption.substring(0, 1).toLowerCase() + caption.substring(1) + ")";
    }
}
