/*
 * Project Scelight
 * 
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 * 
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelightapi.sc2.rep.model.details;

import hu.scelight.sc2.rep.model.details.Race;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SC2 Race.
 * 
 * @author Andras Belicza
 * 
 * @see IEnum
 */
public interface IRace {
	
	/** Terran. */
	IRace         TERRAN     = Race.TERRAN;
	
	/** Zerg. */
	IRace         ZERG       = Race.ZERG;
	
	/** Protoss. */
	IRace         PROTOSS    = Race.PROTOSS;
	
	/** Random. */
	IRace         RANDOM     = Race.RANDOM;
	
	/** Unknown. */
	IRace         UNKNOWN    = Race.UNKNOWN;
	
	
	/** An unmodifiable list of all the races. */
	List< IRace > VALUE_LIST = Collections.unmodifiableList( Arrays.< IRace > asList( Race.VALUES ) );
	
	
	/**
	 * Returns the race letter (first character of the English name except <code>'-'</code> for {@link #UNKNOWN}).
	 * 
	 * @return the race letter (first character of the English name except <code>'-'</code> for {@link #UNKNOWN})
	 */
	char getLetter();
	

}
