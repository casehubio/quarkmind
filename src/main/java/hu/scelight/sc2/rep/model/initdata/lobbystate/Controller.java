/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.initdata.lobbystate;

import hu.scelight.sc2.rep.model.attributesevents.AttributesEvents;
import hu.scelightapi.sc2.rep.model.initdata.lobbystate.IController;

/**
 * Player controller.
 *
 * @author Andras Belicza
 */
public enum Controller implements IController {

	/** Open slot. */
	OPEN( "Open", "Open" ),

	/** Closed slot. */
	CLOSED( "Closed", "Clsd" ),

	/** Human. */
	HUMAN( "Human", "Humn" ),

	/** Computer. */
	COMPUTER( "Computer", "Comp" );


	/** Text value of the player controller. */
	public final String text;

	/** Controller value used for {@link AttributesEvents#A_CONTROLLER}. */
	public final String attrValue;


	/**
	 * Creates a new {@link Controller}.
	 *
	 * @param text text value
	 * @param attrValue controller value used for {@link AttributesEvents#A_CONTROLLER}
	 */
	private Controller( final String text, final String attrValue ) {
		this.text = text;
		this.attrValue = attrValue;
	}

	@Override
	public String toString() {
		return text;
	}


	/** Cache of the values array. */
	public static final Controller[] VALUES = values();

}
