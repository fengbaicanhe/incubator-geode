/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.cache.client.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.GatewayConfigurationException;
import com.gemstone.gemfire.cache.client.ServerRefusedConnectionException;
import com.gemstone.gemfire.cache.client.internal.ServerBlackList.FailureTracker;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.PoolCancelledException;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.internal.cache.PoolManagerImpl;
import com.gemstone.gemfire.internal.cache.tier.Acceptor;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientUpdater;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.HandShake;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.logging.LogService;
import com.gemstone.gemfire.internal.logging.log4j.LocalizedMessage;
import com.gemstone.gemfire.security.GemFireSecurityException;

/**
 * Creates connections, using a connection source to determine
 * which server to connect to.
 * @author dsmith
 * @since 5.7
 * 
 */
public class ConnectionFactoryImpl implements ConnectionFactory {
  
  private static final Logger logger = LogService.getLogger();
  
  //TODO  - the handshake holds state. It seems like the code depends 
  //on all of the handshake operations happening in a single thread. I don't think we
  //want that, need to refactor.
  private final HandShake handshake;
  private final int socketBufferSize;
  private final int handShakeTimeout;
  private final boolean usedByGateway;
  private final ServerBlackList blackList;
  private final CancelCriterion cancelCriterion;
  private ConnectionSource source;
  private int readTimeout;
  private DistributedSystem ds;
  private EndpointManager endpointManager;
  private GatewaySender gatewaySender;
  private PoolImpl pool;
  
  /**
   * Test hook for client version support
   * @since 5.7
   */
  
  public static boolean testFailedConnectionToServer = false;
    
  public ConnectionFactoryImpl(ConnectionSource source,
      EndpointManager endpointManager, DistributedSystem sys,
      int socketBufferSize, int handShakeTimeout, int readTimeout,
      ClientProxyMembershipID proxyId, CancelCriterion cancelCriterion,
      boolean usedByGateway, GatewaySender sender,long pingInterval,
      boolean multiuserSecureMode, PoolImpl pool) {
    this.handshake = new HandShake(proxyId, sys);
    this.handshake.setClientReadTimeout(readTimeout);
    this.source = source;
    this.endpointManager = endpointManager;
    this.ds = sys;
    this.socketBufferSize = socketBufferSize;
    this.handShakeTimeout = handShakeTimeout;
    this.handshake.setMultiuserSecureMode(multiuserSecureMode);
    this.readTimeout = readTimeout;
    this.usedByGateway = usedByGateway;
    this.gatewaySender = sender;
    this.blackList = new ServerBlackList(pingInterval);
    this.cancelCriterion = cancelCriterion;
    this.pool = pool;
  }
  
  public void start(ScheduledExecutorService background) {
    blackList.start(background);
  }

  private byte getCommMode(boolean forQueue) {
    if (this.usedByGateway || (this.gatewaySender != null)) {
      return Acceptor.GATEWAY_TO_GATEWAY;
    } else if(forQueue) {
      return Acceptor.CLIENT_TO_SERVER_FOR_QUEUE;
    } else {
      return Acceptor.CLIENT_TO_SERVER;
    }
  }
  
  public ServerBlackList getBlackList() { 
    return blackList;
  }
  
  public Connection createClientToServerConnection(ServerLocation location, boolean forQueue)  throws GemFireSecurityException {
    ConnectionImpl connection = new ConnectionImpl(this.ds, this.cancelCriterion);
    FailureTracker failureTracker = blackList.getFailureTracker(location);
    
    boolean initialized = false;
    
    try {
      HandShake connHandShake = new HandShake(handshake);
      connection.connect(endpointManager, location, connHandShake,
                         socketBufferSize, handShakeTimeout, readTimeout, getCommMode(forQueue), this.gatewaySender);
      failureTracker.reset();
      connection.setHandShake(connHandShake);
      authenticateIfRequired(connection);
      initialized = true;
    } catch(CancelException e) {
      //propagate this up, don't retry
      throw e;
    } catch(GemFireSecurityException e) {
      //propagate this up, don't retry
      throw e;
    } catch(GatewayConfigurationException e) {
    //propagate this up, don't retry
      throw e;
    } catch(ServerRefusedConnectionException src) {
      //propagate this up, don't retry      	
      logger.warn(LocalizedMessage.create(LocalizedStrings.AutoConnectionSourceImpl_COULD_NOT_CREATE_A_NEW_CONNECTION_TO_SERVER_0, src.getMessage()));
      testFailedConnectionToServer = true;
      throw src;
    } catch (Exception e) {
      if (e.getMessage() != null &&
          (e.getMessage().equals("Connection refused")
           || e.getMessage().equals("Connection reset"))) { // this is the most common case, so don't print an exception
        if (logger.isDebugEnabled()) {
          logger.debug("Unable to connect to {}: connection refused", location);
        }
      } else {//print a warning with the exception stack trace
        logger.warn(LocalizedMessage.create(LocalizedStrings.ConnectException_COULD_NOT_CONNECT_TO_0, location), e);
      }
      testFailedConnectionToServer = true;
    } finally {
      if(!initialized) {
        connection.destroy();
        failureTracker.addFailure();
        connection = null;
      }
    }
    
    return connection;
  }

  private void authenticateIfRequired(Connection conn) {
    cancelCriterion.checkCancelInProgress(null);
    if (!pool.isUsedByGateway() && !pool.getMultiuserAuthentication()) {
      if (conn.getServer().getRequiresCredentials()) {
        if (conn.getServer().getUserId() == -1) {
          Long uniqueID = (Long)AuthenticateUserOp.executeOn(conn, pool);
          conn.getServer().setUserId(uniqueID);
          if (logger.isDebugEnabled()) {
            logger.debug("CFI.authenticateIfRequired() Completed authentication on {}", conn);
          }
        }
      }
    }
  }

  public ServerLocation findBestServer(ServerLocation currentServer, Set excludedServers) {
    if (currentServer != null && source.isBalanced()) {
      return currentServer;
    }
    final Set origExcludedServers = excludedServers;
    excludedServers = new HashSet(excludedServers);
    Set blackListedServers = blackList.getBadServers();  
    excludedServers.addAll(blackListedServers);
    ServerLocation server = source.findReplacementServer(currentServer, excludedServers);
    if (server == null) {
      // Nothing worked! Let's try without the blacklist.
      if (excludedServers.size() > origExcludedServers.size()) {
        // We had some guys black listed so lets give this another whirl.
        server = source.findReplacementServer(currentServer, origExcludedServers);
      }
    }
    if (server == null && logger.isDebugEnabled()) {
      logger.debug("Source was unable to findForReplacement any servers");
    }
    return server;
  }
  
  public Connection createClientToServerConnection(Set excludedServers) throws GemFireSecurityException {
    final Set origExcludedServers = excludedServers;
    excludedServers = new HashSet(excludedServers);
    Set blackListedServers = blackList.getBadServers();  
    excludedServers.addAll(blackListedServers);
    Connection conn = null;
//    long startTime = System.currentTimeMillis();
    RuntimeException fatalException = null;
    boolean tryBlackList = true;
    
    do {
      ServerLocation server = source.findServer(excludedServers);
      if(server == null) {
        
        if(tryBlackList) {
          // Nothing worked! Let's try without the blacklist.
          tryBlackList = false;
          int size = excludedServers.size();
          excludedServers.removeAll(blackListedServers);
          // make sure we didn't remove any of the ones that the caller set not to use
          excludedServers.addAll(origExcludedServers);
          if(excludedServers.size()<size) {
            // We are able to remove some exclusions, so lets give this another whirl.
            continue;
          }
        }
        if (logger.isDebugEnabled()) {
          logger.debug("Source was unable to locate any servers");
        }
        if(fatalException!=null) {
          throw fatalException;
        }
        return null;
      }
    
      try {
        conn = createClientToServerConnection(server, false);
      } catch(CancelException e) {
      //propagate this up immediately
        throw e;
      } catch(GemFireSecurityException e) {
        //propagate this up immediately
        throw e; 
      } catch(GatewayConfigurationException e) {
        //propagate this up immediately
        throw e;
      } catch(ServerRefusedConnectionException srce) {
        fatalException = srce;
        if (logger.isDebugEnabled()) {
          logger.debug("ServerRefusedConnectionException attempting to connect to {}", server , srce);
        }
      } catch (Exception e) {
        logger.warn(LocalizedMessage.create(LocalizedStrings.ConnectException_COULD_NOT_CONNECT_TO_0, server), e);
      }
      
      excludedServers.add(server);
    } while(conn == null);
      
//    if(conn == null) {
//      logger.fine("Unable to create a connection in the allowed time.");
//      
//      if(fatalException!=null) {
//        throw fatalException;
//      }
//    }
    return conn;
  }
  
  public ClientUpdater createServerToClientConnection(Endpoint endpoint,
      QueueManager qManager, boolean isPrimary, ClientUpdater failedUpdater) {
    String clientUpdateName = CacheClientUpdater.CLIENT_UPDATER_THREAD_NAME
    + " on " + endpoint.getMemberId() + " port " + endpoint.getLocation().getPort();
    if (logger.isDebugEnabled()) {
      logger.debug("Establishing: {}", clientUpdateName);
    }
//  Launch the thread
    CacheClientUpdater updater = new CacheClientUpdater(clientUpdateName,
        endpoint.getLocation(), isPrimary, ds, new HandShake(this.handshake), qManager,
        endpointManager, endpoint, handShakeTimeout);
    
    if(!updater.isConnected()) {
      return null;
    }
    
    updater.setFailedUpdater(failedUpdater);
    updater.start();

//  Wait for the client update thread to be ready
//    if (!updater.waitForInitialization()) {
      // Yogesh : This doesn't wait for notify if the updater
      // thread exits from the run in case of Exception in CCU thread
      // Yogesh : fix for 36690
      // because when CCU thread gets a ConnectException, it comes out of run method
      // and when a thread is no more running it notifies all the waiting threads on the thread object.
      // so above wait will come out irrelevant of notify from CCU thread, when CCU thread has got an exception
      // To avoid this problem we check isAlive before returning from this method.
//      if (logger != null && logger.infoEnabled()) {
//        logger.info(LocalizedStrings.AutoConnectionSourceImpl_0_NOT_STARTED_1, new Object[] {this, clientUpdateName});
//      }
//      return null;
//    }else {
//      if (logger != null && logger.infoEnabled()) {
//        logger.info(LocalizedStrings.AutoConnectionSourceImpl_0_STARTED_1, new Object[] {this, clientUpdateName});
//      }
//    }
    return updater;
  }
}


