package io.quarkmind.plugin.coaching;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CoachingEffectivenessTrustRecorder {

    private static final Logger log = Logger.getLogger(CoachingEffectivenessTrustRecorder.class);

    public void record(String correlationId, String outcome, CoachingAdvice advice) {
        log.infof("[COACHING-TRUST] correlationId=%s outcome=%s domain=%s advice=%s",
            correlationId, outcome, advice.domainTag(), advice.advice());
    }
}
