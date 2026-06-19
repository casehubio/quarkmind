/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.belicza.andras.util.type;

import java.nio.charset.StandardCharsets;

/**
 * A {@link String} wrapper which stores the original byte array it was created from.
 *
 * @author Andras Belicza
 */
public class XString {

	/** The source of the string. */
	public final byte[] array;

	/** Lazily created string. */
	private String      value;

	/**
	 * Creates a new {@link XString}.
	 *
	 * @param array byte array source of the string, interpreted as UTF-8 encoded characters
	 */
	public XString( final byte[] array ) {
		this.array = array;
	}

	public byte[] getArray() {
		return array;
	}

	@Override
	public String toString() {
		if ( value == null )
			value = new String( array, StandardCharsets.UTF_8 );

		return value;
	}

}
