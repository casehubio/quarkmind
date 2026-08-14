/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.details;

import hu.scelightapi.sc2.rep.model.details.IResult;

/**
 * Match result of a player.
 *
 * @author Andras Belicza
 */
public enum Result implements IResult {

	/** Unknown. */
	UNKNOWN( "Unknown" ),

	/** Victory. */
	VICTORY( "Victory" ),

	/** Defeat. */
	DEFEAT( "Defeat" ),

	/** Tie. */
	TIE( "Tie" );


	/** Text value of the result. */
	public final String text;

	/** Result letter (first character of the English name). */
	public final char   letter;


	/**
	 * Creates a new {@link Result}.
	 *
	 * @param text text value
	 */
	private Result( final String text ) {
		this.text = text;
		letter = "UNKNOWN".equals( name() ) ? '-' : Character.toUpperCase( text.charAt( 0 ) );
	}

	@Override
	public String toString() {
		return text;
	}

	@Override
	public char getLetter() {
		return letter;
	}


	/** Cache of the values array. */
	public static final Result[] VALUES = values();

}
