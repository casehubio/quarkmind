package io.quarkmind.agent;

import io.quarkmind.domain.ArchetypeCategory;
import io.quarkmind.domain.CounterEntry;
import io.quarkmind.domain.CounterInfo;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SignatureSpec;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.UnitType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class StrategyTaxonomy {

    private final EnumMap<StrategyArchetype, ArchetypeEntry> entries = new EnumMap<>(StrategyArchetype.class);

    @PostConstruct
    public void init() {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("io/quarkmind/domain/strategy-taxonomy.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("strategy-taxonomy.yaml not found on classpath");
            }
            root = yaml.load(stream);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read strategy-taxonomy.yaml", e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> archetypes = (Map<String, Map<String, Object>>) root.get("archetypes");
        if (archetypes == null) {
            throw new IllegalStateException("strategy-taxonomy.yaml missing 'archetypes' root key");
        }

        for (var yamlEntry : archetypes.entrySet()) {
            String key = yamlEntry.getKey();
            StrategyArchetype archetype;
            try {
                archetype = StrategyArchetype.valueOf(key);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("YAML key '" + key + "' does not match any StrategyArchetype enum value");
            }
            entries.put(archetype, parseEntry(archetype, yamlEntry.getValue()));
        }

        for (StrategyArchetype arch : StrategyArchetype.values()) {
            if (!entries.containsKey(arch)) {
                throw new IllegalStateException("StrategyArchetype." + arch.name() + " has no YAML entry in strategy-taxonomy.yaml");
            }
        }
    }

    public ArchetypeEntry lookup(StrategyArchetype archetype) {
        return entries.get(archetype);
    }

    public CounterInfo countersFor(StrategyArchetype archetype) {
        return countersFor(archetype, Race.PROTOSS);
    }

    public CounterInfo countersFor(StrategyArchetype archetype, Race playerRace) {
        ArchetypeEntry entry = entries.get(archetype);
        if (entry == null) {return null;}
        return entry.countersByRace().get(playerRace);
    }


    public List<ArchetypeEntry> forPhase(GamePhase phase) {
        return entries.values().stream()
            .filter(e -> e.phase() == phase)
            .toList();
    }

    public List<SignatureSpec> signaturesForPhase(GamePhase phase) {
        return entries.values().stream()
            .filter(e -> !e.handAuthored())
            .filter(e -> e.phase() == phase)
            .flatMap(e -> e.signatureSpecs().stream())
            .toList();
    }

    public List<SignatureSpec> activeSignatures(double gameTimeMinutes) {
        return entries.values().stream()
                      .filter(e -> !e.handAuthored())
                      .filter(e -> gameTimeMinutes >= e.phaseWindow()[0] && gameTimeMinutes <= e.phaseWindow()[1])
                      .flatMap(e -> e.signatureSpecs().stream())
                      .toList();
    }


    @SuppressWarnings("unchecked")
    private ArchetypeEntry parseEntry(StrategyArchetype archetype, Map<String, Object> data) {
        String            displayName  = (String) data.get("displayName");
        Race              race         = Race.valueOf((String) data.get("race"));
        GamePhase         phase        = GamePhase.valueOf((String) data.get("phase"));
        ArchetypeCategory category     = ArchetypeCategory.valueOf((String) data.get("category"));
        boolean           handAuthored = Boolean.TRUE.equals(data.get("handAuthored"));

        if (race != archetype.race()) {
            throw new IllegalStateException(archetype + " YAML race " + race + " != enum race " + archetype.race());
        }
        if (phase != archetype.phase()) {
            throw new IllegalStateException(archetype + " YAML phase " + phase + " != enum phase " + archetype.phase());
        }
        if (category != archetype.category()) {
            throw new IllegalStateException(archetype + " YAML category " + category + " != enum category " + archetype.category());
        }

        List<Number> windowList = (List<Number>) data.get("phaseWindow");
        double[] phaseWindow = windowList != null
                               ? new double[]{windowList.get(0).doubleValue(), windowList.get(1).doubleValue()}
                               : new double[]{0.0, 30.0};
        if (phaseWindow[0] > phaseWindow[1]) {
            throw new IllegalStateException(archetype + " invalid phaseWindow: start > end");
        }

        List<SignatureSpec>        signatureSpecs = parseSignature(archetype, data, phaseWindow);
        EnumMap<Race, CounterInfo> countersByRace = parseMatchupCounters(archetype, data);
        List<String> detectionSignals = data.containsKey("detectionSignals")
                                        ? (List<String>) data.get("detectionSignals")
                                        : List.of();

        return new ArchetypeEntry(archetype, displayName, race, phase, category,
                                  phaseWindow, handAuthored, signatureSpecs, countersByRace, detectionSignals);
    }

    @SuppressWarnings("unchecked")
    private List<SignatureSpec> parseSignature(StrategyArchetype archetype, Map<String, Object> data, double[] phaseWindow) {
        Map<String, Object> sig = (Map<String, Object>) data.get("signature");
        if (sig == null) return List.of();

        boolean noExpansion = Boolean.TRUE.equals(sig.get("noExpansion"));
        List<Map<String, Object>> units = (List<Map<String, Object>>) sig.get("units");
        if (units == null) return List.of();

        List<SignatureSpec> specs = new ArrayList<>();
        for (Map<String, Object> unitSpec : units) {
            UnitType unitType = UnitType.valueOf((String) unitSpec.get("type"));
            int minCount = ((Number) unitSpec.get("minCount")).intValue();
            specs.add(new SignatureSpec(archetype, unitType, minCount,
                phaseWindow[0], phaseWindow[1], 0.5, noExpansion, archetype.race()));
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private EnumMap<Race, CounterInfo> parseMatchupCounters(StrategyArchetype archetype, Map<String, Object> data) {
        Map<String, Map<String, Object>> counters = (Map<String, Map<String, Object>>) data.get("counters");
        if (counters == null) {
            throw new IllegalStateException(archetype + " missing 'counters' section in YAML");
        }
        EnumMap<Race, CounterInfo> result = new EnumMap<>(Race.class);
        for (var raceEntry : counters.entrySet()) {
            Race                playerRace = Race.valueOf(raceEntry.getKey());
            Map<String, Object> matchup    = raceEntry.getValue();
            List<CounterEntry>  strong     = parseCounterList((List<Map<String, Object>>) matchup.get("strong"));
            List<CounterEntry>  weak       = parseCounterList((List<Map<String, Object>>) matchup.get("weak"));
            result.put(playerRace, new CounterInfo(strong, weak));
        }
        for (Race r : Race.values()) {
            if (!result.containsKey(r)) {
                throw new IllegalStateException(archetype + " missing counters for player race " + r);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<CounterEntry> parseCounterList(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        List<CounterEntry> result = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            List<String> unitNames = (List<String>) entry.get("units");
            List<UnitType> units = unitNames.stream()
                .map(UnitType::valueOf)
                .toList();
            String action = (String) entry.get("action");
            result.add(new CounterEntry(units, action));
        }
        return result;
    }

    public record ArchetypeEntry(
            StrategyArchetype archetype,
            String displayName,
            Race race,
            GamePhase phase,
            ArchetypeCategory category,
            double[] phaseWindow,
            boolean handAuthored,
            List<SignatureSpec> signatureSpecs,
            EnumMap<Race, CounterInfo> countersByRace,
            List<String> detectionSignals
    ) {
        public ArchetypeEntry {
            phaseWindow = phaseWindow.clone();
        }
    }

}
