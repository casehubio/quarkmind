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

import hu.scelight.sc2.rep.model.details.Result;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Match result of a player.
 * 
 * @author Andras Belicza
 * 
 * @see IEnum
 */
public interface IResult {
	
	/** Unknown. */
	IResult         UNKNOWN    = Result.UNKNOWN;
	
	/** Victory. */
	IResult         VICTORY    = Result.VICTORY;
	
	/** Defeat. */
	IResult         DEFEAT     = Result.DEFEAT;
	
	/** Tie. */
	IResult         TIE        = Result.TIE;
	
	
	/** An unmodifiable list of all the results. */
	List< IResult > VALUE_LIST = Collections.unmodifiableList( Arrays.< IResult > asList( Result.VALUES ) );
	
	
	/**
	 * Returns the result letter (first character of the English name except <code>'-'</code> for {@link #UNKNOWN}).
	 * 
	 * @return the result letter (first character of the English name except <code>'-'</code> for {@link #UNKNOWN})
	 */
	char getLetter();
	

}
