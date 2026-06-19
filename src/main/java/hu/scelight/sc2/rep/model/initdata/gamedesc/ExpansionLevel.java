/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.model.initdata.gamedesc;

import hu.scelightapi.sc2.rep.model.initdata.gamedesc.IExpansionLevel;

/**
 * Expansion level.
 *
 * @author Andras Belicza
 */
public enum ExpansionLevel implements IExpansionLevel {

	/** Legacy of the Void. */
	// "Standard Data: Void.SC2Mod"
	LOTV( "LotV", "Legacy of the Void", "d92dfc48c484c59154270b924ad7d57484f2ab9a47621c7ab16431bf66c53b40" ),

	/** Heart of the Swarm. */
	// "Standard Data: Swarm.SC2Mod"
	HOTS( "HotS", "Heart of the Swarm", "66093832128453efffbb787c80b7d3eec1ad81bde55c83c930dea79c4e505a04" ),

	/** Wings of Liberty. */
	// "Standard Data: Liberty.SC2Mod"
	WOL( "WoL", "Wings of Liberty", "421c8aa0f3619b652d23a2735dfee812ab644228235e7a797edecfe8b67da30e" ),

	/** Unknown. */
	UNKNOWN( "Unknown", "Unknown", "" );


	/** Text value of the expansion level. */
	public final String text;

	/** Full (long) text value of the expansion level. */
	public final String fullText;

	/** Content digest of the cache handle that defines the expansion level. */
	public final String cacheHandleDigest;


	/**
	 * Creates a new {@link ExpansionLevel}.
	 *
	 * @param text text value
	 * @param fullText full (long) text value
	 * @param cacheHandleDigest content digest of the cache handle that defines the expansion level
	 */
	private ExpansionLevel( final String text, final String fullText, final String cacheHandleDigest ) {
		this.text = text;
		this.fullText = fullText;
		this.cacheHandleDigest = cacheHandleDigest;
	}

	@Override
	public String toString() {
		return text;
	}

	@Override
	public String getFullText() {
		return fullText;
	}


	/** Cache of the values array. */
	public static final ExpansionLevel[] VALUES = values();

}
