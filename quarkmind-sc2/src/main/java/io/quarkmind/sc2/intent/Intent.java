package io.quarkmind.sc2.intent;

public sealed interface Intent permits BuildIntent, TrainIntent, AttackIntent, MoveIntent, BlinkIntent, MuleCalldownIntent, ResearchIntent, MorphIntent, AbilityIntent {
}
