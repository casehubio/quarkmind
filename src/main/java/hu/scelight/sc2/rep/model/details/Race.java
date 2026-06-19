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

import hu.scelightapi.sc2.rep.model.details.IRace;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * SC2 Race.
 *
 * @author Andras Belicza
 */
public enum Race implements IRace {

	/**
	 * Terran.
	 */
	TERRAN( "Terran", new HashSet<>( Arrays.asList( "Terran", "Terraner", "Terrano", "테란", "人類",
	        "Терран", "Терраны", "Terrani", "人类" ) ) ),

	/**
	 * Zerg.
	 */
	ZERG( "Zerg", new HashSet<>( Arrays.asList( "Zerg", "저그", "蟲族", "Зерг", "Зерги", "Zergi", "异虫" ) ) ),

	/**
	 * Protoss.
	 */
	PROTOSS( "Protoss", new HashSet<>( Arrays.asList( "Protoss", "프로토스", "神族", "Протосс", "Протоссы",
	        "Protosi", "星灵" ) ) ),

	/** Random. */
	RANDOM( "Random", Collections.emptySet() ),

	/** Unknown. */
	UNKNOWN( "Unknown", Collections.emptySet() );


	/** Text value of the race. */
	public final String         text;

	/** Race letter (first character of the English name). */
	public final char           letter;

	/** Localized names of the race. */
	private final Set< String > localizedNameSet;

	/**
	 * Creates a new {@link Race}.
	 *
	 * @param text text value
	 * @param localizedNameSet localized name set
	 */
	private Race( final String text, final Set< String > localizedNameSet ) {
		this.text = text;
		letter = "UNKNOWN".equals( name() ) ? '-' : Character.toUpperCase( text.charAt( 0 ) );
		this.localizedNameSet = localizedNameSet;
	}

	/**
	 * Returns the race specified by its localized name.
	 *
	 * @param localizedName the localized name of the race
	 * @return the race specified by its localized name; or {@link #UNKNOWN} if the localized value was not recognized
	 */
	public static Race fromLocalizedValue( final String localizedName ) {
		if ( ZERG.localizedNameSet.contains( localizedName ) )
			return ZERG;
		if ( PROTOSS.localizedNameSet.contains( localizedName ) )
			return PROTOSS;
		if ( TERRAN.localizedNameSet.contains( localizedName ) )
			return TERRAN;

		if ( localizedName.startsWith( "Pr" ) )
			return PROTOSS;
		else if ( localizedName.startsWith( "Te" ) )
			return TERRAN;
		else if ( localizedName.startsWith( "Ze" ) )
			return ZERG;

		return UNKNOWN;
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
	public static final Race[] VALUES = values();

}
