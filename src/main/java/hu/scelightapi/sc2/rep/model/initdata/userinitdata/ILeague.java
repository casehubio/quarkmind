/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelightapi.sc2.rep.model.initdata.userinitdata;

import hu.scelight.sc2.rep.model.initdata.userinitdata.League;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Battle.net league.
 *
 * @author Andras Belicza
 */
public interface ILeague {

	/** Unknown. */
	ILeague         UNKNOWN     = League.UNKNOWN;

	/** Bronze. */
	ILeague         BRONZE      = League.BRONZE;

	/** Silver. */
	ILeague         SILVER      = League.SILVER;

	/** Gold. */
	ILeague         GOLD        = League.GOLD;

	/** Platinum. */
	ILeague         PLATINUM    = League.PLATINUM;

	/** Diamond. */
	ILeague         DIAMOND     = League.DIAMOND;

	/** Master. */
	ILeague         MASTER      = League.MASTER;

	/** Grandmaster. */
	ILeague         GRANDMASTER = League.GRANDMASTER;

	/** Unranked. */
	ILeague         UNRANKED    = League.UNRANKED;


	/** An unmodifiable list of all the leagues. */
	List< ILeague > VALUE_LIST  = Collections.unmodifiableList( Arrays.< ILeague > asList( League.VALUES ) );

	/**
	 * Returns the league letter.
	 *
	 * @return the league letter
	 */
	char getLetter();

}
