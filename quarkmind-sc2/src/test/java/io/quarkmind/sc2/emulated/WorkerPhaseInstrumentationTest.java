package io.quarkmind.sc2.emulated;

import io.quarkmind.sc2.playbook.SC2DeliveryHandler;
import io.quarkmind.sc2.playbook.SC2PlaybookRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPhaseInstrumentationTest {

    record PhaseEvent(String tag, WorkerPhase from, WorkerPhase to, double loop) {}
    record DeliveryEvent(String tag, double minerals, double loop) {}

    @Test
    void phaseListener_capturesCycleTransitions_at8Workers() {
        var game = new EmulatedGame();
        var handler = new SC2DeliveryHandler(game);
        var runner = new SC2PlaybookRunner(handler, game);

        List<PhaseEvent> phases = new ArrayList<>();
        List<DeliveryEvent> deliveries = new ArrayList<>();

        game.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
            @Override
            public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {
                phases.add(new PhaseEvent(tag, from, to, loop));
            }

            @Override
            public void onDelivery(String tag, double minerals, double loop) {
                deliveries.add(new DeliveryEvent(tag, minerals, loop));
            }
        });

        runner.execute("playbooks/cal-mineral-baseline-8w.yaml");

        assertThat(phases).isNotEmpty();
        assertThat(deliveries).isNotEmpty();
        assertThat(deliveries).allMatch(d -> d.minerals() == 5.0 || d.minerals() == 7.0);
    }

    @Tag("diagnostic")
    @Test
    void phaseBreakdown_8w_printsCycleTimingPerWorker() {
        var game = new EmulatedGame();
        var handler = new SC2DeliveryHandler(game);
        var runner = new SC2PlaybookRunner(handler, game);

        Map<String, List<PhaseEvent>> perWorker = new LinkedHashMap<>();
        List<DeliveryEvent> deliveries = new ArrayList<>();

        game.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
            @Override
            public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {
                perWorker.computeIfAbsent(tag, k -> new ArrayList<>())
                         .add(new PhaseEvent(tag, from, to, loop));
            }

            @Override
            public void onDelivery(String tag, double minerals, double loop) {
                deliveries.add(new DeliveryEvent(tag, minerals, loop));
            }
        });

        runner.execute("playbooks/cal-mineral-baseline-8w.yaml");

        System.out.println("=== EM Phase Breakdown (8w) ===");
        System.out.printf("Total phase events: %d, deliveries: %d%n%n", phases(perWorker), deliveries.size());

        for (var entry : perWorker.entrySet()) {
            String tag = entry.getKey();
            List<PhaseEvent> events = entry.getValue();

            List<Double> walkOut = new ArrayList<>();
            List<Double> mining = new ArrayList<>();
            List<Double> walkBack = new ArrayList<>();
            List<Double> depositing = new ArrayList<>();

            for (int i = 0; i < events.size() - 1; i++) {
                PhaseEvent current = events.get(i);
                PhaseEvent next = events.get(i + 1);
                double duration = next.loop() - current.loop();
                switch (current.to()) {
                    case WALKING_TO_PATCH -> walkOut.add(duration);
                    case MINING -> mining.add(duration);
                    case WALKING_TO_BASE -> walkBack.add(duration);
                    case DEPOSITING -> depositing.add(duration);
                    case WAITING_FOR_PATCH -> {}
                }
            }

            if (walkOut.isEmpty()) continue;

            System.out.printf("Worker %s — %d cycles:%n", tag, walkOut.size());
            System.out.printf("  walk→patch: mean=%.1f  min=%.1f  max=%.1f%n",
                    mean(walkOut), min(walkOut), max(walkOut));
            System.out.printf("  mining:     mean=%.1f  min=%.1f  max=%.1f%n",
                    mean(mining), min(mining), max(mining));
            System.out.printf("  walk→base:  mean=%.1f  min=%.1f  max=%.1f%n",
                    mean(walkBack), min(walkBack), max(walkBack));
            System.out.printf("  depositing: mean=%.1f  min=%.1f  max=%.1f%n",
                    mean(depositing), min(depositing), max(depositing));
            double totalMean = mean(walkOut) + mean(mining) + mean(walkBack) + mean(depositing);
            System.out.printf("  TOTAL cycle: %.1f loops (%.2f ticks)%n%n", totalMean, totalMean / 22.0);
        }

        long deliveryCount = deliveries.stream().map(DeliveryEvent::tag).distinct().count();
        System.out.printf("Workers with deliveries: %d%n", deliveryCount);
        System.out.printf("Total deliveries: %d, total minerals: %.0f%n",
                deliveries.size(), deliveries.stream().mapToDouble(DeliveryEvent::minerals).sum());
    }

    private static int phases(Map<String, List<PhaseEvent>> map) {
        return map.values().stream().mapToInt(List::size).sum();
    }

    private static double mean(List<Double> vals) {
        return vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double min(List<Double> vals) {
        return vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    private static double max(List<Double> vals) {
        return vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
}