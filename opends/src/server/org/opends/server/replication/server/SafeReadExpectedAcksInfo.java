/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2008 Sun Microsystems, Inc.
 */

package org.opends.server.replication.server;

import static org.opends.server.loggers.debug.DebugLogger.*;

import org.opends.server.loggers.debug.DebugTracer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opends.server.replication.common.AssuredMode;
import org.opends.server.replication.common.ChangeNumber;
import org.opends.server.replication.protocol.AckMsg;

/**
 * This class holds every info needed about the expected acks for a received
 * update message requesting assured replication with Safe Read sub-mode.
 * It also includes info/routines for constructing the final ack to be sent to
 * the sender of the update message.
 */
public class SafeReadExpectedAcksInfo extends ExpectedAcksInfo
{
  /**
   * The tracer object for the debug logger.
   */
  private static final DebugTracer TRACER = getTracer();

  // Did some servers go in timeout when the matching update was sent ?
  private boolean hasTimeout = false;

  // Were some servers in wrong status when the matching update was sent ?
  private boolean hasWrongStatus = false;

  // Did some servers make an error replaying the sent matching update ?
  private boolean hasReplayError = false;

  // The list of server ids that had errors for the sent matching update
  // Each server id of the list had one of the
  // 3 possible errors (timeout, wrong status or replay error)
  private List<Short> failedServers = null;

  /**
   * This gives the list of servers we are willing to wait acks from and the
   * information about the ack from the servers.
   * key: the id of the server.
   * value: a boolean true if we received the ack from the server,
   * false otherwise.
   * This must not include servers we already identified they are in wrong
   * status, but just servers that are in normal status.
   */
  private Map<Short,Boolean> expectedServersAckStatus =
    new HashMap<Short,Boolean>();

  /**
   * Number of servers we want an ack from and from which we received the ack.
   * Said differently: the number of servers in expectedServersAckStatus whose
   * value is true. When this value reaches the size of expectedServersAckStatus
   * we can compute an ack message (based on info in this object), to be
   * returned to the (requester) server that sent us an assured update message.
   */
  private int numKnownAckStatus = 0;

  /**
   * Creates a new SafeReadExpectedAcksInfo.
   * @param changeNumber The change number of the assured update message
   * @param requesterServerHandler The server that sent the assured update
   * message
   * @param expectedServers The list of servers we want an ack from (they are
   * in normal status and have the same group id as us)
   * @param wrongStatusServers The list of all servers already detected in
   * wrongStatus (degraded status) to keep trace of the error for the future
   * returning ack we gonna compute
   */
  public SafeReadExpectedAcksInfo(ChangeNumber changeNumber,
    ServerHandler requesterServerHandler, List<Short> expectedServers,
    List<Short> wrongStatusServers)
  {
    super(changeNumber, requesterServerHandler, AssuredMode.SAFE_READ_MODE);

    // Keep track of potential servers detected in wrong status
    if (wrongStatusServers.size() > 0)
    {
      hasWrongStatus = true;
      failedServers = wrongStatusServers;
    }

    // Initialize list of servers we expect acks from
    for (Short serverId : expectedServers)
    {
      expectedServersAckStatus.put(serverId, false);
    }
  }

  /**
   * Sets the timeout marker for the future update ack.
   * @param hasTimeout True if some timeout occurred
   */
  public void setHasTimeout(boolean hasTimeout)
  {
    this.hasTimeout = hasTimeout;
  }

  /**
   * Sets the wrong status marker for the future update ack.
   * @param hasWrongStatus True if some servers were in wrong status
   */
  public void setHasWrongStatus(boolean hasWrongStatus)
  {
    this.hasWrongStatus = hasWrongStatus;
  }

  /**
   * Sets the replay error marker for the future update ack.
   * @param hasReplayError True if some servers had errors replaying the change
   */
  public void setHasReplayError(boolean hasReplayError)
  {
    this.hasReplayError = hasReplayError;
  }

  /**
   * Gets the timeout marker for the future update ack.
   * @return The timeout marker for the future update ack.
   */
  public boolean hasTimeout()
  {
    return hasTimeout;
  }

  /**
   * Gets the wrong status marker for the future update ack.
   * @return hasWrongStatus The wrong status marker for the future update ack.
   */
  public boolean hasWrongStatus()
  {
    return hasWrongStatus;
  }

  /**
   * Gets the replay error marker for the future update ack.
   * @return hasReplayError The replay error marker for the future update ack.
   */
  public boolean hasReplayError()
  {
    return hasReplayError;
  }

  /**
   * {@inheritDoc}
   */
  public boolean processReceivedAck(ServerHandler ackingServer, AckMsg ackMsg)
  {
    // Get the ack status for the matching server
    short ackingServerId = ackingServer.getServerId();
    boolean ackReceived = expectedServersAckStatus.get(ackingServerId);
    if (ackReceived)
    {
      // Sanity check: this should never happen
      if (debugEnabled())
        TRACER.debugInfo("Received unexpected ack from server id: "
          + ackingServerId + " ack message: " + ackMsg);
        return false;
    } else
    {
      // Analyze received ack and update info for the ack to be later computed
      // accordingly
      boolean someErrors = false;
      if (ackMsg.hasTimeout())
      {
        hasTimeout = true;
        someErrors = true;
      }
      if (ackMsg.hasWrongStatus())
      {
        hasWrongStatus = true;
        someErrors = true;
      }
      if (ackMsg.hasReplayError())
      {
        hasReplayError = true;
        someErrors = true;
      }
      if (someErrors)
      {
        failedServers.addAll(ackMsg.getFailedServers());
      }

      // Mark this ack received for the server
      expectedServersAckStatus.put(ackingServerId, true);
      numKnownAckStatus++;
    }

    return (numKnownAckStatus == expectedServersAckStatus.size());
  }

  /**
   * {@inheritDoc}
   */
  public AckMsg createAck(boolean timeout)
  {
    AckMsg ack = new AckMsg(changeNumber);

    // Fill collected errors info
    ack.setHasTimeout(hasTimeout);
    ack.setHasWrongStatus(hasWrongStatus);
    ack.setHasReplayError(hasReplayError);
    ack.setFailedServers(failedServers);

    // Force anyway timeout flag if requested
    if (timeout)
      ack.setHasTimeout(true);

    return ack;
  }
}
