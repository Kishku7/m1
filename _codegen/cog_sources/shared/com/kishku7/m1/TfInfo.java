package com.kishku7.m1;

/** Version-agnostic description of a text field. Built by Platform (per version). */
public final class TfInfo {
    public final String id;
    public final String text;
    public final boolean focused;
    public final boolean visible;

    public TfInfo(String id, String text, boolean focused, boolean visible) {
        this.id = id;
        this.text = text == null ? "" : text;
        this.focused = focused;
        this.visible = visible;
    }
}
