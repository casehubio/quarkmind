/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.initdata.userinitdata;

import hu.scelightapi.sc2.rep.model.initdata.userinitdata.ILeague;

/**
 * Battle.net league.
 *
 * @author Andras Belicza
 */
public enum League implements ILeague {

	/** Unknown. */
	UNKNOWN( "Unknown" ),

	/** Bronze. */
	BRONZE( "Bronze" ),

	/** Silver. */
	SILVER( "Silver" ),

	/** Gold. */
	GOLD( "Gold" ),

	/** Platinum. */
	PLATINUM( "Platinum" ),

	/** Diamond. */
	DIAMOND( "Diamond" ),

	/** Master. */
	MASTER( "Master" ),

	/** Grandmaster. */
	GRANDMASTER( "Grandmaster" ),

	/** Unranked. */
	UNRANKED( "Unranked" );


	/** Text value of the league. */
	public final String text;

	/**
	 * League letter (first character of the English name except <code>'R'</code> for {@link #GRANDMASTER} and <code>'-'</code> for {@link #UNKNOWN}).
	 */
	public final char   letter;


	/**
	 * Creates a new {@link League}.
	 *
	 * @param text text value
	 */
	private League( final String text ) {
		this.text = text;
		letter = "UNKNOWN".equals( name() ) ? '-' : Character.toUpperCase( "GRANDMASTER".equals( name() ) ? 'R' : text.charAt( 0 ) );
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
	public static final League[] VALUES = values();

}
