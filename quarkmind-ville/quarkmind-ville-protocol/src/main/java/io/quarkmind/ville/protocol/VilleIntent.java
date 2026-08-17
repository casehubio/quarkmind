package io.quarkmind.ville.protocol;

import io.quarkmind.agency.intent.Intent;

public sealed interface VilleIntent extends Intent {

    record Move(Position target) implements VilleIntent {}
    record Talk(String text) implements VilleIntent {}
    record Rest() implements VilleIntent {}
    record Emote(String emote) implements VilleIntent {}
}
