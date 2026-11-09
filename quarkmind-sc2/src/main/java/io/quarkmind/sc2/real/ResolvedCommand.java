package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.Optional;

record ResolvedCommand(Tag tag, Abilities ability, Optional<Point2d> target, Optional<Long> targetUnitTag) {

    ResolvedCommand(Tag tag, Abilities ability, Optional<Point2d> target) {
        this(tag, ability, target, Optional.empty());
    }

    static ResolvedCommand targetUnit(Tag casterTag, Abilities ability, long targetTag) {
        return new ResolvedCommand(casterTag, ability, Optional.empty(), Optional.of(targetTag));
    }
}
