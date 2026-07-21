package com.kishku7.m1;

/** Version-agnostic description of an enumerable button/widget. Built by Platform (per version). */
public final class WidgetInfo {
    public final int id;
    public final String label;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final boolean active;
    public final boolean visible;

    public WidgetInfo(int id, String label, int x, int y, int w, int h, boolean active, boolean visible) {
        this.id = id;
        this.label = label;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.active = active;
        this.visible = visible;
    }
}
