package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Basic tactics: act on the {@link QuarkMindCaseFile#STRATEGY} key each tick.
 *
 * <ul>
 *   <li><b>ATTACK</b> — queue {@link AttackIntent} for each army unit toward
 *       {@link #MAP_CENTER}. No directed movement — superseded implementation.</li>
 *   <li><b>DEFEND</b> — queue {@link MoveIntent} for each army unit to rally
 *       near our Nexus.</li>
 *   <li><b>MACRO</b> — no-op; army holds position.</li>
 * </ul>
 *
 * <p>Superseded by {@link DroolsTacticsTask} as the active CDI bean. Retained as
 * a plain class for direct-instantiation tests.
 *
 * <p>This class intentionally carries no CDI annotations ({@code @ApplicationScoped},
 * {@code @CaseType}) — {@link DroolsTacticsTask} is the permanent active bean.
 * Direct instantiation only: never injected by the container.
 */
public class BasicTacticsTask implements TacticsTask {

    /** Generic attack target when no scouted enemy position is available. */
    static final Point2d MAP_CENTER = new Point2d(64, 64);

    private static final Logger log = Logger.getLogger(BasicTacticsTask.class);

    private final IntentQueue intentQueue;

    @Inject
    public BasicTacticsTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "tactics.basic"; }
    @Override public String getName() { return "Basic Tactics"; }
    @Override public Set<String> entryCriteria() {
        return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY);
    }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    /**
     * Overrides the {@code TaskDefinition} default, which unconditionally returns {@code true}
     * in the installed casehub-core snapshot — ignoring {@link #entryCriteria()}.
     * Override required until the foundation corrects the default.
     */
    @Override
    public boolean canActivate(CaseFile caseFile) {
        return entryCriteria().stream().allMatch(caseFile::contains);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        String strategy = caseFile.get(QuarkMindCaseFile.STRATEGY, String.class).orElse("MACRO");
        List<Unit>     army      = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ARMY,         List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(QuarkMindCaseFile.MY_BUILDINGS, List.class).orElse(List.of());

        if (army.isEmpty()) return;

        switch (strategy) {
            case "ATTACK" -> executeAttack(army);
            case "DEFEND" -> executeDefend(army, buildings);
            // MACRO: hold position
        }

        log.debugf("[TACTICS] %s | army=%d", strategy, army.size());
    }

    private void executeAttack(List<Unit> army) {
        // BasicTacticsTask always attacks MAP_CENTER — superseded by DroolsTacticsTask
        army.forEach(unit -> intentQueue.add(new AttackIntent(unit.tag(), MAP_CENTER)));
    }

    private void executeDefend(List<Unit> army, List<Building> buildings) {
        Point2d rallyPoint = buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(MAP_CENTER);
        army.forEach(unit -> intentQueue.add(new MoveIntent(unit.tag(), rallyPoint)));
    }
}
