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

import hu.scelightapi.sc2.rep.model.initdata.gamedesc.IBnetLang;

/**
 * Available Battle.net languages on the Battle.net web.
 *
 * @author Andras Belicza
 *
 * @see Region
 */
public enum BnetLang implements IBnetLang {

	/** English. */
	ENGLISH( "English", "en" ),

	/** Chinese (Traditional). */
	CHINESE_TRADITIONAL( "Chinese (Traditional)", "zh" ),

	/** French. */
	FRENCH( "French", "fr" ),

	/** German. */
	GERMAN( "German", "de" ),

	/** Italian. */
	ITALIAN( "Italian", "it" ),

	/** Korean. */
	KOREAN( "Korean", "ko" ),

	/** Polish. */
	POLISH( "Polish", "pl" ),

	/** Portuguese. */
	PORTUGUESE( "Portuguese", "pt" ),

	/** Russian. */
	RUSSIAN( "Russian", "ru" ),

	/** Spanish. */
	SPANISH( "Spanish", "es" );


	/** Text value of the bnet language. */
	public final String text;

	/** Language code, the way it appears in URLs. */
	public final String langCode;


	/**
	 * Creates a new {@link BnetLang}.
	 *
	 * @param text text value
	 * @param langCode language code
	 */
	private BnetLang( final String text, final String langCode ) {
		this.text = text;
		this.langCode = langCode;
	}

	@Override
	public String getLangCode() {
		return langCode;
	}

	@Override
	public String toString() {
		return text;
	}


	/** Cache of the values array. */
	public static final BnetLang[] VALUES = values();

}
