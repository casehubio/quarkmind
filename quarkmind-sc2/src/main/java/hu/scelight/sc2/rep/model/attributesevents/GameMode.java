/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.attributesevents;

import hu.scelightapi.sc2.rep.model.attributesevents.IGameMode;

/**
 * Game mode.
 *
 * @author Andras Belicza
 */
public enum GameMode implements IGameMode {

	/** AutoMM (Automatic/Anonymous Matchmaking), for example ladder games. */
	AMM( "AutoMM", "Amm" ),

	/** Private. */
	PRIVATE( "Private", "Priv" ),

	/** Public. */
	PUBLIC( "Public", "Pub" ),

	/** Single player. */
	SINGLE_PLAYER( "Single Player", "" ),

	/** Unknown. */
	UNKNOWN( "Unknown", "<>" );


	/** Text value of the game mode. */
	public final String text;

	/** Game mode value used for for {@link AttributesEvents#A_GAME_MODE}. */
	public final String attrValue;


	/**
	 * Creates a new {@link GameMode}.
	 *
	 * @param text text value
	 * @param attrValue game mode value used for for {@link AttributesEvents#A_GAME_MODE}
	 */
	private GameMode( final String text, final String attrValue ) {
		this.text = text;
		this.attrValue = attrValue;
	}

	@Override
	public String toString() {
		return text;
	}

	/**
	 * Returns the game mode for the specified raw value.
	 *
	 * @param value raw value of the game mode
	 * @return the game mode for the specified raw value; or {@link #UNKNOWN} if no game mode is found for the specified raw value
	 */
	public static GameMode fromValue( final String value ) {
		for ( final GameMode mode : VALUES )
			if ( mode.attrValue.equals( value ) )
				return mode;

		return UNKNOWN;
	}


	/** Cache of the values array. */
	public static final GameMode[] VALUES = values();

}
