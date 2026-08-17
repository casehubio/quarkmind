package io.quarkmind.ville.protocol;

import io.quarkmind.agency.intent.Intent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VilleIntentTest {

    @Test
    void moveIsAnIntent() {
        VilleIntent intent = new VilleIntent.Move(new Position(10.0, 20.0, 0.0));
        assertThat(intent).isInstanceOf(Intent.class);
    }

    @Test
    void talkCarriesText() {
        var talk = new VilleIntent.Talk("Hello!");
        assertThat(talk.text()).isEqualTo("Hello!");
    }

    @Test
    void restHasNoFields() {
        var rest = new VilleIntent.Rest();
        assertThat(rest).isInstanceOf(VilleIntent.class);
    }

    @Test
    void emoteCarriesType() {
        var emote = new VilleIntent.Emote("wave");
        assertThat(emote.emote()).isEqualTo("wave");
    }

    @Test
    void sealedHierarchyCoversAllTypes() {
        assertThat(VilleIntent.class.getPermittedSubclasses()).hasSize(4);
    }
}
