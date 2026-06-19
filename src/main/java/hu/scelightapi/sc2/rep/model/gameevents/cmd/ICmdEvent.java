/*
 * Project Scelight
 *
 * Copyright (c) 2013 Andras Belicza <iczaaa@gmail.com>
 *
 * This software is the property of Andras Belicza.
 * Copying, modifying, distributing, refactoring without the author's permission
 * is prohibited and protected by Law.
 */
package hu.scelightapi.sc2.rep.model.gameevents.cmd;

import hu.scelightapi.sc2.rep.model.IEvent;
import hu.sllauncher.util.Pair;

/**
 * Cmd game event.
 *
 * @author Andras Belicza
 */
public interface ICmdEvent extends IEvent {

	/** Cmd flags field name. */
	String   F_CMD_FLAGS      = "cmdFlags";

	/** Ability link (index) field path. */
	String[] P_ABIL_LINK      = { "abil", "abilLink" };

	/** Ability cmd index field path. */
	String[] P_ABIL_CMD_INDEX = { "abil", "abilCmdIndex" };

	/** Data field name. */
	String   F_DATA           = "data";


	/**
	 * Returns the cmd flags.
	 *
	 * @return the cmd flags
	 */
	Integer getCmdFlags();

	/**
	 * Returns the abil link (index).
	 *
	 * @return the abil link (index)
	 */
	Integer getAbilLink();

	/**
	 * Returns the abil cmd index.
	 *
	 * @return the abil cmd index
	 */
	Integer getAbilCmdIndex();

	/**
	 * Returns the data.
	 *
	 * @return the data
	 */
	Pair< String, Object > getData();

	/**
	 * Returns the target unit of the command.
	 *
	 * @return the target unit of the command
	 */
	ITargetUnit getTargetUnit();

	/**
	 * Returns the target point of the command.
	 *
	 * @return the target point of the command
	 */
	ITargetPoint getTargetPoint();

}
