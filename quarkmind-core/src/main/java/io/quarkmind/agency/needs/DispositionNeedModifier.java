package io.quarkmind.agency.needs;

public interface DispositionNeedModifier {

    double modifyDecayRate(String need, double baseRate);
}
