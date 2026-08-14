package io.quarkmind.agency.spi;

import io.quarkmind.agency.intent.Intent;
import io.quarkmind.agency.intent.IntentQueue;

public interface WorldBridge<P extends WorldPerception, I extends Intent> {

    void connect();

    void disconnect();

    P perceive();

    void dispatch(IntentQueue<I> intents);
}
