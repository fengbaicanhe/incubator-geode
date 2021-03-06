/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
   
   
package com.gemstone.gemfire.internal.admin.remote;

import java.io.DataInput;
//import com.gemstone.gemfire.internal.*;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.admin.RuntimeAdminException;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.PooledDistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
//import java.util.*;
//import java.net.*;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.logging.LogService;
import com.gemstone.gemfire.internal.logging.log4j.LocalizedMessage;

/**
 * A message that is sent to a particular distribution manager to
 * make an administration request.
 */
public abstract class AdminRequest extends PooledDistributionMessage  {

  private static final Logger logger = LogService.getLogger();
  
  private String modifiedClasspath = "";
  protected transient String friendlyName = "";


  // instance variables

  /** The reply procesor used to gathering replies to an
   * AdminRequest.  See bug 31562. */
  private transient AdminReplyProcessor processor;

  /** The (reply processor) id of this message */
  protected int msgId;

  // static methods

  // constructors
  public AdminRequest() {

  }

  // instance methods

  public int getMsgId() {
    return this.msgId;
  }

 
  /**
   * Sends this request, waits for the AdminReponse, and returns it
   */
  public AdminResponse sendAndWait(DistributionManager dm) {
    InternalDistributedMember recipient = this.getRecipient();
    if (dm.getId().equals(recipient)) {
      // We're sending this message to ourselves, we won't need a
      // reply process.  Besides, if we try to create one, we'll get
      // an assertion failure.
      this.msgId = -1;

    } else {
      this.processor =
        new AdminReplyProcessor(dm.getSystem(), recipient);
      this.msgId = this.processor.getProcessorId();
    }

    return AdminWaiters.sendAndWait(this, dm);
  }

  /**
   * Waits a given number of milliseconds for the reply to this
   * request. 
   *
   * @return Whether or not a reply was received.
   *
   * @see #getResponse
   */
  boolean waitForResponse(long timeout) throws InterruptedException {
//    if (Thread.interrupted()) throw new InterruptedException(); not necessary waitForReplies does this?
    try {
      return this.processor.waitForReplies(timeout);

    } catch (ReplyException ex) {
      for (Throwable cause = ex.getCause(); cause != null;
           cause = cause.getCause()) {
        if (cause instanceof RuntimeAdminException) {
          throw (RuntimeAdminException) cause;
        }
      }

      throw new RuntimeAdminException(LocalizedStrings.AdminRequest_A_REPLYEXCEPTION_WAS_THROWN_WHILE_WAITING_FOR_A_REPLY.toLocalizedString(), ex);
    }
  }

  /**
   * Returns the response to this <code>AdminRequest</code>.
   *
   * @see AdminReplyProcessor#getResponse
   */
  AdminResponse getResponse() {
    return this.processor.getResponse();
  }

  /**
   * This method is invoked on the receiver side. It creates a response
   * message and puts it on the outgoing queue.
   */
  @Override
  protected void process(DistributionManager dm) {
    AdminResponse response = null;
    InspectionClasspathManager cpMgr = InspectionClasspathManager.getInstance();
    try {
      cpMgr.jumpToModifiedClassLoader(modifiedClasspath);
      response = createResponse(dm);
    } catch (Exception ex) {
      response = AdminFailureResponse.create(dm, this.getSender(), ex);
    } finally {
      cpMgr.revertToOldClassLoader();
    }
    if (response != null) { //cancellations result in null response
      response.setMsgId(this.getMsgId());
      dm.putOutgoing(response);
    } else {
      logger.info(LocalizedMessage.create(LocalizedStrings.AdminRequest_RESPONSE_TO__0__WAS_CANCELLED, this.getClass().getName()));
    }
  }
  /**
   * Must return a proper response to this request.
   */
  protected abstract AdminResponse createResponse(DistributionManager dm);

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    out.writeInt(this.msgId);
    DataSerializer.writeString(this.modifiedClasspath, out);
  }

  @Override
  public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.msgId = in.readInt();
    this.modifiedClasspath = DataSerializer.readString(in);
  }

  public void setModifiedClasspath(String path) {
    if (path == null) {
      this.modifiedClasspath = "";
    } else {
      this.modifiedClasspath = path;
    }
  }

  public InternalDistributedMember getRecipient() {
    InternalDistributedMember[] recipients = getRecipients();
    int size = recipients.length;
    if (size == 0) {
      return null;
    } else if (size > 1) {
      throw new
        IllegalStateException(LocalizedStrings.AdminRequest_COULD_NOT_RETURN_ONE_RECIPIENT_BECAUSE_THIS_MESSAGE_HAS_0_RECIPIENTS.toLocalizedString(Integer.valueOf(size)));
    } else {
      return recipients[0];
    }
  }

}
