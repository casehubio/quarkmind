package io.quarkmind.ville.protocol;

public record VilleEvent(String type, String from, String text, long tick) {}
