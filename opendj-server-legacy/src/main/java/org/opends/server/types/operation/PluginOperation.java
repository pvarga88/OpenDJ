/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2008 Sun Microsystems, Inc.
 * Portions Copyright 2014 ForgeRock AS.
 */
package org.opends.server.types.operation;

import java.util.List;
import java.util.Map;

import org.forgerock.i18n.LocalizableMessage;
import org.opends.server.api.ClientConnection;
import org.opends.server.controls.ControlDecoder;
import org.opends.server.types.CanceledOperationException;
import org.opends.server.types.Control;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.DisconnectReason;
import org.opends.server.types.OperationType;

/**
 * This class defines a set of methods that are available for use by
 * all types of plugins involved in operation processing (pre-parse,
 * pre-operation, post-operation, post-response, search result entry,
 * search result reference, and intermediate response).  Note that
 * this interface is intended only to define an API for use by plugins
 * and is not intended to be implemented by any custom classes.
 */
@org.opends.server.types.PublicAPI(
     stability=org.opends.server.types.StabilityLevel.UNCOMMITTED,
     mayInstantiate=false,
     mayExtend=false,
     mayInvoke=true)
public interface PluginOperation
{
  /**
   * Retrieves the operation type for this operation.
   *
   * @return  The operation type for this operation.
   */
  OperationType getOperationType();



  /**
   * Retrieves the client connection with which this operation is
   * associated.
   *
   * @return  The client connection with which this operation is
   *          associated.
   */
  ClientConnection getClientConnection();



  /**
   * Terminates the client connection being used to process this
   * operation.  The plugin must return a result indicating that the
   * client connection has been terminated.
   *
   * @param  disconnectReason  The disconnect reason that provides the
   *                           generic cause for the disconnect.
   * @param  sendNotification  Indicates whether to try to provide
   *                           notification to the client that the
   *                           connection will be closed.
   * @param  message           The message to send to the client.  It
   *                           may be <CODE>null</CODE> if no
   *                           notification is to be sent.
   */
  void disconnectClient(DisconnectReason disconnectReason, boolean sendNotification, LocalizableMessage message);



  /**
   * Retrieves the unique identifier that is assigned to the client
   * connection that submitted this operation.
   *
   * @return  The unique identifier that is assigned to the client
   *          connection that submitted this operation.
   */
  long getConnectionID();



  /**
   * Retrieves the operation ID for this operation.
   *
   * @return  The operation ID for this operation.
   */
  long getOperationID();



  /**
   * Retrieves the message ID assigned to this operation.
   *
   * @return  The message ID assigned to this operation.
   */
  int getMessageID();



  /**
   * Retrieves the set of controls included in the request from the
   * client.  The contents of this list must not be altered.
   *
   * @return  The set of controls included in the request from the
   *          client.
   */
  List<Control> getRequestControls();



  /**
   * Retrieves a control included in the request from the client.
   *
   * @param <T>
   *          The type of control requested.
   * @param d
   *          The requested control's decoder.
   * @return The decoded form of the requested control included in the
   *         request from the client or <code>null</code> if the
   *         control was not found.
   * @throws DirectoryException
   *           if an error occurs while decoding the control.
   */
  <T extends Control> T getRequestControl(ControlDecoder<T> d) throws DirectoryException;



  /**
   * Retrieves the set of controls to include in the response to the
   * client.  The contents of this list must not be altered.
   *
   * @return  The set of controls to include in the response to the
   *          client.
   */
  List<Control> getResponseControls();



  /**
   * Indicates whether this is an internal operation rather than one
   * that was requested by an external client.
   *
   * @return  <CODE>true</CODE> if this is an internal operation, or
   *          <CODE>false</CODE> if it is not.
   */
  boolean isInternalOperation();



  /**
   * Indicates whether this is a synchronization operation rather than
   * one that was requested by an external client.
   *
   * @return  <CODE>true</CODE> if this is a data synchronization
   *          operation, or <CODE>false</CODE> if it is not.
   */
  boolean isSynchronizationOperation();



  /**
   * Retrieves the set of attachments defined for this operation, as a
   * mapping between the attachment name and the associated object.
   *
   * @return  The set of attachments defined for this operation.
   */
  Map<String,Object> getAttachments();



  /**
   * Retrieves the attachment with the specified name.
   *
   * @param <T> the type of the attached object
   * @param  name  The name for the attachment to retrieve.  It will
   *               be treated in a case-sensitive manner.
   *
   * @return  The requested attachment object, or <CODE>null</CODE> if
   *          it does not exist.
   */
  <T> T getAttachment(String name);



  /**
   * Removes the attachment with the specified name.
   *
   * @param <T> the type of the attached object
   * @param  name  The name for the attachment to remove.  It will be
   *               treated in a case-sensitive manner.
   *
   * @return  The attachment that was removed, or <CODE>null</CODE> if
   *          it does not exist.
   */
  <T> T removeAttachment(String name);



  /**
   * Sets the value of the specified attachment.  If an attachment
   * already exists with the same name, it will be replaced.
   * Otherwise, a new attachment will be added.
   *
   * @param <T> the type of the attached object
   * @param  name   The name to use for the attachment.
   * @param  value  The value to use for the attachment.
   *
   * @return  The former value held by the attachment with the given
   *          name, or <CODE>null</CODE> if there was previously no
   *          such attachment.
   */
  <T> T setAttachment(String name, Object value);



  /**
   * Retrieves the time that processing started for this operation.
   *
   * @return  The time that processing started for this operation.
   */
  long getProcessingStartTime();



  /**
   * Retrieves a string representation of this operation.
   *
   * @return  A string representation of this operation.
   */
  @Override
  String toString();



  /**
   * Appends a string representation of this operation to the provided
   * buffer.
   *
   * @param  buffer  The buffer into which a string representation of
   *                 this operation should be appended.
   */
  void toString(StringBuilder buffer);



  /**
   * Checks to see if this operation requested to cancel in which case
   * CanceledOperationException will be thrown.
   *
   * @param signalTooLate <code>true</code> to signal that any further
   *                      cancel requests will be too late after
   *                      return from this call or <code>false</code>
   *                      otherwise.
   *
   * @throws CanceledOperationException if this operation should
   * be cancelled.
   */
  void checkIfCanceled(boolean signalTooLate) throws CanceledOperationException;
}

