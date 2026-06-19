/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.gameevents.cmd;

import hu.belicza.andras.util.ArrayMap;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.sllauncher.util.Pair;

import java.util.Map;

/**
 * Cmd game event.
 *
 * @author Andras Belicza
 */
public class CmdEvent extends Event {

	/** Cached flags. */
	private final int         flags;

	/** Target unit of the cmd event. */
	private final TargetUnit  targetUnit;

	/** Target point of the cmd event. */
	private final TargetPoint targetPoint;


	/**
	 * Creates a new {@link CmdEvent}.
	 *
	 * @param struct event data structure
	 * @param id id of the event
	 * @param name type name of the event
	 * @param loop game loop when the event occurred
	 * @param userId user id causing the event
	 * @param baseBuild base build of the replay being parsed, there are structural differences in event structures in different versions
	 * @param balanceData ignored (null in standalone mode)
	 */
	public CmdEvent( final Map< String, Object > struct, final int id, final String name, final int loop, final int userId, final int baseBuild,
	        final Object balanceData ) {
		super( struct, id, name, loop, userId );

		// In the first retail version (1.0) fields of the abil and data structs are enumerated
		// directly in the cmd struct (except the target point). Simulate those structs.
		if ( baseBuild < 16561 ) {
			final Integer abilLink = (Integer) struct.remove( "abilLink" );
			if ( abilLink != 65535 ) { // in 1.0 65535 means right click (which is also indicated in the cmdFlags)
				final Map< String, Object > abilStruct = new ArrayMap< >( 2 );
				abilStruct.put( "abilLink", abilLink );
				abilStruct.put( "abilCmdIndex", struct.remove( "abilCmdIndex" ) );
				struct.put( "abil", abilStruct );
			}

			@SuppressWarnings( "unchecked" )
			final Map< String, Object > targetPoint_ = (Map< String, Object >) struct.remove( "targetPoint" );

			final Integer targetUnitTag = (Integer) struct.remove( "targetUnitTag" );
			final Integer targetUnitSnapshotLink = (Integer) struct.remove( "targetUnitSnapshotUnitLink" );
			if ( targetUnitSnapshotLink != null && targetUnitSnapshotLink != 0 ) {
				// Simulate a "TargetUnit"
				final Pair< String, Map< String, Object > > data = new Pair< String, Map< String, Object > >( "TargetUnit",
				        new ArrayMap< String, Object >( 5 ) );
				data.value2.put( TargetUnit.F_TARGET_UNIT_FLAGS, struct.remove( "targetUnitFlags" ) );
				data.value2.put( TargetUnit.F_TIMER, struct.remove( "targetUnitTimer" ) );
				data.value2.put( TargetUnit.F_TAG, targetUnitTag );
				data.value2.put( TargetUnit.F_SNAPSHOT_UNIT_LINK, targetUnitSnapshotLink );
				data.value2.put( TargetUnit.F_SNAPSHOT_PLAYER_ID, struct.remove( "targetUnitSnapshotPlayerId" ) );
				// Does the simulated "TargetUnit" have a snapshotPoint?
				if ( (Integer) targetPoint_.get( "x" ) != 0 )
					data.value2.put( TargetUnit.F_SNAPSHOT_POINT, targetPoint_ );
				struct.put( "data", data );
			} else if ( (Integer) targetPoint_.get( "x" ) != 0 ) {
				// Simulate a "TargetPoint"
				final Pair< String, Map< String, Object > > data = new Pair< >( "TargetPoint", targetPoint_ );
				struct.put( "data", data );
			} else if ( targetUnitTag != 0 ) {
				// Simulate a "Data"
				final Pair< String, Integer > data = new Pair< >( "Data", targetUnitTag );
				struct.put( "data", data );
			}
		}

		flags = get( "cmdFlags" );

		// Cache target
		final Pair< String, Object > data = getData();
		if ( data == null ) {
			targetUnit = null;
			targetPoint = null;
		} else
			switch ( data.value1 ) {
				case "TargetUnit" :
					@SuppressWarnings( "unchecked" )
					final Map< String, Object > tuStruct = (Map< String, Object >) data.value2;
					targetUnit = new TargetUnit( tuStruct );
					targetPoint = new TargetPoint( targetUnit.getSnapshotPoint() );
					break;
				case "TargetPoint" :
					targetUnit = null;
					@SuppressWarnings( "unchecked" )
					final Map< String, Object > tpStruct = (Map< String, Object >) data.value2;
					targetPoint = new TargetPoint( tpStruct );
					break;
				case "Data" :
				default :
					targetUnit = null;
					targetPoint = null;
					break;
			}
	}

	public Integer getCmdFlags() {
		return flags;
	}

	public Integer getAbilLink() {
		return get( new String[] { "abil", "abilLink" } );
	}

	public Integer getAbilCmdIndex() {
		return get( new String[] { "abil", "abilCmdIndex" } );
	}

	public Pair< String, Object > getData() {
		return get( "data" );
	}

	public TargetUnit getTargetUnit() {
		return targetUnit;
	}

	public TargetPoint getTargetPoint() {
		return targetPoint;
	}

}
