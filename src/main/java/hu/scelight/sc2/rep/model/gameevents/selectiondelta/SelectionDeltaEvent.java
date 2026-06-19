/*
 * Project Scelight
 * 
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 * 
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.gameevents.selectiondelta;

import hu.belicza.andras.util.type.BitArray;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelight.sc2.rep.s2prot.type.Field;
import hu.scelightapi.sc2.rep.model.gameevents.cmd.ITagTransformation;
import hu.scelightapi.sc2.rep.model.gameevents.selectiondelta.ISelectionDeltaEvent;
import hu.sllauncher.util.Pair;

import java.util.Map;

/**
 * Camera update game event.
 * 
 * @author Andras Belicza
 */
public class SelectionDeltaEvent extends Event implements ISelectionDeltaEvent {
	
	/** Old path to get the remove mask. */
	private static final String[] P_OLD_REMOVE_MASK = { "delta", Delta.F_REMOVE_MASK, Field.NAME_PARENT };
	
	
	/** Selection delta. */
	private Delta                 delta;
	
	
	/**
	 * Creates a new {@link SelectionDeltaEvent}.
	 * 
	 * @param struct event data structure
	 * @param id id of the event
	 * @param name type name of the event
	 * @param loop game loop when the event occurred
	 * @param userId user id causing the event
	 * @param baseBuild base build of the replay being parsed, there are structural differences in event structures in different versions
	 */
	public SelectionDeltaEvent( final Map< String, Object > struct, final int id, final String name, final int loop, final int userId, final int baseBuild ) {
		super( struct, id, name, loop, userId );
		
		// In the first retail version (1.0) removeMask is a structure holding always the deselection bitmap
		// with the key NAME_PARENT. Simulate the new remove mask;
		if ( baseBuild < 16561 ) {
			// The same key is used to set the simulated new remove mask, so it will also remove the old one.
			getDelta_().put( Delta.F_REMOVE_MASK, new Pair<>( "Mask", get( P_OLD_REMOVE_MASK ) ) );
		}
	}
	
	@Override
	public Integer getControlGroupId() {
		return get( F_GROUP_INDEX );
	}
	
	/**
	 * @return the delta struct
	 */
	private Map< String, Object > getDelta_() {
		return get( F_DELTA );
	}
	
	@Override
	public Delta getDelta() {
		if ( delta == null )
			delta = new Delta( getDelta_() );
		
		return delta;
	}
	

}
