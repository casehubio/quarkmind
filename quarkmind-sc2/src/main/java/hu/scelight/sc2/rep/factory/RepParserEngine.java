/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelight.sc2.rep.factory;

import hu.belicza.andras.mpq.InvalidMpqArchiveException;
import hu.belicza.andras.mpq.MpqParser;
import hu.scelight.sc2.rep.model.Header;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.attributesevents.AttributesEvents;
import hu.scelight.sc2.rep.model.details.Details;
import hu.scelight.sc2.rep.model.gameevents.GameEvents;
import hu.scelight.sc2.rep.model.initdata.InitData;
import hu.scelight.sc2.rep.model.messageevents.MessageEvents;
import hu.scelight.sc2.rep.model.trackerevents.TrackerEvents;
import hu.scelight.sc2.rep.s2prot.Protocol;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Replay parser engine.<br>
 * Able to parse StarCraft II replay files and construct {@link Replay} objects.
 *
 * @author Andras Belicza
 */
public class RepParserEngine {

	private static final Logger LOGGER = Logger.getLogger( RepParserEngine.class );

	/** Empty replay content set. */
	public static final Set< RepContent > EMPTY_CONTENT_SET       = EnumSet.noneOf( RepContent.class );

	/** Replay content set containing only the game events. */
	public static final Set< RepContent > GAME_EVENTS_CONTENT_SET = EnumSet.of( RepContent.GAME_EVENTS );

	/** Full replay content set. */
	public static final Set< RepContent > FULL_CONTENT_SET        = EnumSet.of( RepContent.MESSAGE_EVENTS, RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS );


	/**
	 * Parses the specified replay file and returns a {@link Replay} object.
	 *
	 * @param file replay file to be parsed
	 * @return the constructed {@link Replay} object or <code>null</code> if the replay cannot be parsed
	 */
	public static Replay parseReplay( final Path file ) {
		return parseReplay( file, FULL_CONTENT_SET );
	}

	/**
	 * Parses the specified replay file and returns a {@link Replay} object.
	 *
	 * @param file replay file to be parsed
	 * @param contentSet content to be parsed; {@link RepContent#DETAILS}, {@link RepContent#INIT_DATA} and {@link RepContent#ATTRIBUTES_EVENTS} are always
	 *            parsed; extra content is to be specified here
	 * @return the constructed {@link Replay} object or <code>null</code> if the replay cannot be parsed
	 */
	public static Replay parseReplay( final Path file, final Set< RepContent > contentSet ) {
		try ( final MpqParser mpqParser = new MpqParser( file ) ) {

			return parseReplay( mpqParser, contentSet );

		} catch ( final Exception e ) {
			LOGGER.debugf( e, "Failed to parse replay: %s", file );
			return null;
		}
	}


	/**
	 * Parses the specified replay file and returns a {@link Replay} object.
	 *
	 * @param mpqParser MPQ parser providing the replay content
	 * @param contentSet content to be parsed
	 * @return the constructed {@link Replay} object or <code>null</code> if the replay could not be parsed
	 * @throws InvalidMpqArchiveException if error occurs reading files from the MPQ archive
	 */
	private static Replay parseReplay( final MpqParser mpqParser, final Set< RepContent > contentSet ) throws InvalidMpqArchiveException {
		final Replay replay = new Replay();

		// Read replay header, this can be read with any protocol
		replay.header = new Header( Protocol.DEFAULT.decodeHeader( mpqParser.getUserData().userData ) );

		final Protocol p = Protocol.get( replay.header.getBaseBuild() );
		if ( p == null ) {
			LOGGER.infof( "Unsupported Replay version: %s (base build: %d)%s",
			        replay.header.versionString(),
			        replay.header.getBaseBuild(),
			        mpqParser.getFileName() == null ? "!" : ": " + mpqParser.getFileName() );
			return null;
		}

		// Contents that are always parsed:

		byte[] data = mpqParser.getFile( RepContent.DETAILS );
		if ( data == null ) {
			// Try to open anonymized version:
			data = mpqParser.getFile( RepContent.DETAILS_BACKUP );
		}
		replay.details = new Details( p.decodeDetails( data ) );

		data = mpqParser.getFile( RepContent.INIT_DATA );
		if ( data == null ) {
			// Try to open anonymized version:
			data = mpqParser.getFile( RepContent.INIT_DATA_BACKUP );
		}
		replay.initData = new InitData( p.decodeInitData( data ) );

		replay.attributesEvents = new AttributesEvents( p.decodeAttributesEvents( mpqParser.getFile( RepContent.ATTRIBUTES_EVENTS ) ) );

		// Optionally parsed contents:

		if ( contentSet.contains( RepContent.MESSAGE_EVENTS ) ) {
			data = mpqParser.getFile( RepContent.MESSAGE_EVENTS );
			if ( data == null ) { // Might be missing in case of anonymized replays
				data = new byte[ 0 ];
			}
			replay.messageEvents = new MessageEvents( p.decodeMessageEvents( data, replay ) );
		}

		if ( contentSet.contains( RepContent.GAME_EVENTS ) )
			replay.gameEvents = new GameEvents( p.decodeGameEvents( mpqParser.getFile( RepContent.GAME_EVENTS ), replay ) );

		if ( contentSet.contains( RepContent.TRACKER_EVENTS ) ) {
			final byte[] trackerData = mpqParser.getFile( RepContent.TRACKER_EVENTS );
			if ( trackerData != null )
				replay.trackerEvents = new TrackerEvents( p.decodeTrackerEvents( trackerData, replay ) );
		}

		return replay;
	}

}
