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
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.server.extensions;

import static org.opends.messages.ExtensionMessages.*;
import static org.opends.server.config.ConfigConstants.*;
import static org.opends.server.protocols.internal.InternalClientConnection.*;
import static org.opends.server.protocols.ldap.LDAPConstants.*;
import static org.opends.server.util.StaticUtils.*;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IllegalFormatConversionException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.MissingFormatArgumentException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.LocalizedIllegalArgumentException;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.server.ConfigChangeResult;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.config.server.ConfigurationChangeListener;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.DecodeException;
import org.forgerock.opendj.ldap.DereferenceAliasesPolicy;
import org.forgerock.opendj.ldap.Filter;
import org.forgerock.opendj.ldap.GeneralizedTime;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.SearchScope;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.forgerock.opendj.ldap.schema.Schema;
import org.forgerock.opendj.server.config.meta.LDAPPassThroughAuthenticationPolicyCfgDefn.MappingPolicy;
import org.forgerock.opendj.server.config.server.LDAPPassThroughAuthenticationPolicyCfg;
import org.opends.server.api.AuthenticationPolicy;
import org.opends.server.api.AuthenticationPolicyFactory;
import org.opends.server.api.AuthenticationPolicyState;
import org.opends.server.api.DirectoryThread;
import org.opends.server.api.PasswordStorageScheme;
import org.opends.server.api.TrustManagerProvider;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ModifyOperation;
import org.opends.server.core.ServerContext;
import org.opends.server.protocols.ldap.BindRequestProtocolOp;
import org.opends.server.protocols.ldap.BindResponseProtocolOp;
import org.opends.server.protocols.ldap.ExtendedResponseProtocolOp;
import org.opends.server.protocols.ldap.LDAPMessage;
import org.opends.server.protocols.ldap.ProtocolOp;
import org.opends.server.protocols.ldap.SearchRequestProtocolOp;
import org.opends.server.protocols.ldap.SearchResultDoneProtocolOp;
import org.opends.server.protocols.ldap.SearchResultEntryProtocolOp;
import org.opends.server.protocols.ldap.UnbindRequestProtocolOp;
import org.opends.server.schema.SchemaConstants;
import org.opends.server.schema.UserPasswordSyntax;
import org.opends.server.tools.LDAPReader;
import org.opends.server.tools.LDAPWriter;
import org.opends.server.types.Attribute;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.Entry;
import org.opends.server.types.HostPort;
import org.opends.server.types.InitializationException;
import org.opends.server.types.LDAPException;
import org.opends.server.types.RawFilter;
import org.opends.server.types.RawModification;
import org.opends.server.types.SearchFilter;
import org.opends.server.util.StaticUtils;
import org.opends.server.util.TimeThread;

/** LDAP pass through authentication policy implementation. */
public final class LDAPPassThroughAuthenticationPolicyFactory implements
    AuthenticationPolicyFactory<LDAPPassThroughAuthenticationPolicyCfg>
{
  // TODO: handle password policy response controls? AD?
  // TODO: custom aliveness pings
  // TODO: improve debug logging and error messages.

  /**
   * A simplistic load-balancer connection factory implementation using
   * approximately round-robin balancing.
   */
  static abstract class AbstractLoadBalancer implements ConnectionFactory,
      Runnable
  {
    /** A connection which automatically retries operations on other servers. */
    private final class FailoverConnection implements Connection
    {
      private Connection connection;
      private MonitoredConnectionFactory factory;
      private final int startIndex;
      private int nextIndex;

      private FailoverConnection(final int startIndex)
          throws DirectoryException
      {
        this.startIndex = nextIndex = startIndex;

        DirectoryException lastException;
        do
        {
          factory = factories[nextIndex];
          if (factory.isAvailable)
          {
            try
            {
              connection = factory.getConnection();
              incrementNextIndex();
              return;
            }
            catch (final DirectoryException e)
            {
              // Ignore this error and try the next factory.
              logger.traceException(e);
              lastException = e;
            }
          }
          else
          {
            lastException = factory.lastException;
          }
          incrementNextIndex();
        }
        while (nextIndex != startIndex);

        // All the factories have been tried so give up and throw the exception.
        throw lastException;
      }

      @Override
      public void close()
      {
        connection.close();
      }

      @Override
      public ByteString search(final DN baseDN, final SearchScope scope,
          final SearchFilter filter) throws DirectoryException
      {
        for (;;)
        {
          try
          {
            return connection.search(baseDN, scope, filter);
          }
          catch (final DirectoryException e)
          {
            logger.traceException(e);
            handleDirectoryException(e);
          }
        }
      }

      @Override
      public void simpleBind(final ByteString username,
          final ByteString password) throws DirectoryException
      {
        for (;;)
        {
          try
          {
            connection.simpleBind(username, password);
            return;
          }
          catch (final DirectoryException e)
          {
            logger.traceException(e);
            handleDirectoryException(e);
          }
        }
      }

      private void handleDirectoryException(final DirectoryException e)
          throws DirectoryException
      {
        // If the error does not indicate that the connection has failed, then
        // pass this back to the caller.
        if (!isServiceError(e.getResultCode()))
        {
          throw e;
        }

        // The associated server is unavailable, so close the connection and
        // try the next connection factory.
        connection.close();
        factory.lastException = e;
        factory.isAvailable = false; // publishes lastException

        while (nextIndex != startIndex)
        {
          factory = factories[nextIndex];
          if (factory.isAvailable)
          {
            try
            {
              connection = factory.getConnection();
              incrementNextIndex();
              return;
            }
            catch (final DirectoryException de)
            {
              // Ignore this error and try the next factory.
              logger.traceException(de);
            }
          }
          incrementNextIndex();
        }

        // All the factories have been tried so give up and throw the exception.
        throw e;
      }

      private void incrementNextIndex()
      {
        // Try the next index.
        if (++nextIndex == maxIndex)
        {
          nextIndex = 0;
        }
      }
    }

    /**
     * A connection factory which caches its online/offline state in order to
     * avoid unnecessary connection attempts when it is known to be offline.
     */
    private final class MonitoredConnectionFactory implements ConnectionFactory
    {
      private final ConnectionFactory factory;

      /** IsAvailable acts as memory barrier for lastException. */
      private volatile boolean isAvailable = true;
      private DirectoryException lastException;

      private MonitoredConnectionFactory(final ConnectionFactory factory)
      {
        this.factory = factory;
      }

      @Override
      public void close()
      {
        factory.close();
      }

      @Override
      public Connection getConnection() throws DirectoryException
      {
        try
        {
          final Connection connection = factory.getConnection();
          isAvailable = true;
          return connection;
        }
        catch (final DirectoryException e)
        {
          logger.traceException(e);
          lastException = e;
          isAvailable = false; // publishes lastException
          throw e;
        }
      }
    }

    private final MonitoredConnectionFactory[] factories;
    private final int maxIndex;
    private final ScheduledFuture<?> monitorFuture;

    /**
     * Creates a new abstract load-balancer.
     *
     * @param factories
     *          The list of underlying connection factories.
     * @param scheduler
     *          The monitoring scheduler.
     */
    AbstractLoadBalancer(final ConnectionFactory[] factories,
        final ScheduledExecutorService scheduler)
    {
      this.factories = new MonitoredConnectionFactory[factories.length];
      this.maxIndex = factories.length;

      for (int i = 0; i < maxIndex; i++)
      {
        this.factories[i] = new MonitoredConnectionFactory(factories[i]);
      }

      this.monitorFuture = scheduler.scheduleWithFixedDelay(this, 5, 5,
          TimeUnit.SECONDS);
    }

    /** Close underlying connection pools. */
    @Override
    public final void close()
    {
      monitorFuture.cancel(true);

      for (final ConnectionFactory factory : factories)
      {
        factory.close();
      }
    }

    @Override
    public final Connection getConnection() throws DirectoryException
    {
      final int startIndex = getStartIndex();
      return new FailoverConnection(startIndex);
    }

    /** Try to connect to any offline connection factories. */
    @Override
    public void run()
    {
      for (final MonitoredConnectionFactory factory : factories)
      {
        if (!factory.isAvailable)
        {
          try
          {
            factory.getConnection().close();
          }
          catch (final DirectoryException e)
          {
            logger.traceException(e);
          }
        }
      }
    }

    /**
     * Return the start which should be used for the next connection attempt.
     *
     * @return The start which should be used for the next connection attempt.
     */
    abstract int getStartIndex();
  }

  /**
   * A factory which returns pre-authenticated connections for searches.
   * <p>
   * Package private for testing.
   */
  static final class AuthenticatedConnectionFactory implements
      ConnectionFactory
  {
    private final ConnectionFactory factory;
    private final DN username;
    private final String password;

    /**
     * Creates a new authenticated connection factory which will bind on
     * connect.
     *
     * @param factory
     *          The underlying connection factory whose connections are to be
     *          authenticated.
     * @param username
     *          The username taken from the configuration.
     * @param password
     *          The password taken from the configuration.
     */
    AuthenticatedConnectionFactory(final ConnectionFactory factory,
        final DN username, final String password)
    {
      this.factory = factory;
      this.username = username;
      this.password = password;
    }

    @Override
    public void close()
    {
      factory.close();
    }

    @Override
    public Connection getConnection() throws DirectoryException
    {
      final Connection connection = factory.getConnection();
      if (username != null && !username.isRootDN() && password != null
          && password.length() > 0)
      {
        try
        {
          connection.simpleBind(ByteString.valueOfUtf8(username.toString()),
              ByteString.valueOfUtf8(password));
        }
        catch (final DirectoryException e)
        {
          connection.close();
          throw e;
        }
      }
      return connection;
    }
  }

  /** An LDAP connection which will be used in order to search for or authenticate users. */
  static interface Connection extends Closeable
  {
    /** Closes this connection. */
    @Override
    void close();

    /**
     * Returns the name of the user whose entry matches the provided search
     * criteria. This will return CLIENT_SIDE_NO_RESULTS_RETURNED/NO_SUCH_OBJECT
     * if no search results were returned, or CLIENT_SIDE_MORE_RESULTS_TO_RETURN
     * if too many results were returned.
     *
     * @param baseDN
     *          The search base DN.
     * @param scope
     *          The search scope.
     * @param filter
     *          The search filter.
     * @return The name of the user whose entry matches the provided search
     *         criteria.
     * @throws DirectoryException
     *           If the search returned no entries, more than one entry, or if
     *           the search failed unexpectedly.
     */
    ByteString search(DN baseDN, SearchScope scope, SearchFilter filter)
        throws DirectoryException;

    /**
     * Performs a simple bind for the user.
     *
     * @param username
     *          The user name (usually a bind DN).
     * @param password
     *          The user's password.
     * @throws DirectoryException
     *           If the credentials were invalid, or the authentication failed
     *           unexpectedly.
     */
    void simpleBind(ByteString username, ByteString password)
        throws DirectoryException;
  }

  /**
   * An interface for obtaining connections: users of this interface will obtain
   * a connection, perform a single operation (search or bind), and then close
   * it.
   */
  static interface ConnectionFactory extends Closeable
  {
    /**
     * {@inheritDoc}
     * <p>
     * Must never throw an exception.
     */
    @Override
    void close();

    /**
     * Returns a connection which can be used in order to search for or
     * authenticate users.
     *
     * @return The connection.
     * @throws DirectoryException
     *           If an unexpected error occurred while attempting to obtain a
     *           connection.
     */
    Connection getConnection() throws DirectoryException;
  }



  /**
   * PTA connection pool.
   * <p>
   * Package private for testing.
   */
  static final class ConnectionPool implements ConnectionFactory
  {
    /** Pooled connection's intercept close and release connection back to the pool. */
    private final class PooledConnection implements Connection
    {
      private Connection connection;
      private boolean connectionIsClosed;

      private PooledConnection(final Connection connection)
      {
        this.connection = connection;
      }

      @Override
      public void close()
      {
        if (!connectionIsClosed)
        {
          connectionIsClosed = true;

          // Guarded by PolicyImpl
          if (poolIsClosed)
          {
            connection.close();
          }
          else
          {
            connectionPool.offer(connection);
          }

          connection = null;
          availableConnections.release();
        }
      }

      @Override
      public ByteString search(final DN baseDN, final SearchScope scope,
          final SearchFilter filter) throws DirectoryException
      {
        try
        {
          return connection.search(baseDN, scope, filter);
        }
        catch (final DirectoryException e1)
        {
          // Fail immediately if the result indicates that the operation failed
          // for a reason other than connection/server failure.
          reconnectIfConnectionFailure(e1);

          // The connection has failed, so retry the operation using the new
          // connection.
          try
          {
            return connection.search(baseDN, scope, filter);
          }
          catch (final DirectoryException e2)
          {
            // If the connection has failed again then give up: don't put the
            // connection back in the pool.
            closeIfConnectionFailure(e2);
            throw e2;
          }
        }
      }

      @Override
      public void simpleBind(final ByteString username,
          final ByteString password) throws DirectoryException
      {
        try
        {
          connection.simpleBind(username, password);
        }
        catch (final DirectoryException e1)
        {
          // Fail immediately if the result indicates that the operation failed
          // for a reason other than connection/server failure.
          reconnectIfConnectionFailure(e1);

          // The connection has failed, so retry the operation using the new
          // connection.
          try
          {
            connection.simpleBind(username, password);
          }
          catch (final DirectoryException e2)
          {
            // If the connection has failed again then give up: don't put the
            // connection back in the pool.
            closeIfConnectionFailure(e2);
            throw e2;
          }
        }
      }

      private void closeIfConnectionFailure(final DirectoryException e)
          throws DirectoryException
      {
        if (isServiceError(e.getResultCode()))
        {
          connectionIsClosed = true;
          connection.close();
          connection = null;
          availableConnections.release();
        }
      }

      private void reconnectIfConnectionFailure(final DirectoryException e)
          throws DirectoryException
      {
        if (!isServiceError(e.getResultCode()))
        {
          throw e;
        }

        // The connection has failed (e.g. idle timeout), so repeat the
        // request on a new connection.
        connection.close();
        try
        {
          connection = factory.getConnection();
        }
        catch (final DirectoryException e2)
        {
          // Give up - the server is unreachable.
          connectionIsClosed = true;
          connection = null;
          availableConnections.release();
          throw e2;
        }
      }
    }

    /** Guarded by PolicyImpl.lock. */
    private boolean poolIsClosed;

    private final ConnectionFactory factory;
    private final int poolSize = Runtime.getRuntime().availableProcessors() * 2;
    private final Semaphore availableConnections = new Semaphore(poolSize);
    private final Queue<Connection> connectionPool = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new connection pool for the provided factory.
     *
     * @param factory
     *          The underlying connection factory whose connections are to be
     *          pooled.
     */
    ConnectionPool(final ConnectionFactory factory)
    {
      this.factory = factory;
    }

    /** Release all connections: do we want to block? */
    @Override
    public void close()
    {
      // No need for synchronization as this can only be called with the
      // policy's exclusive lock.
      poolIsClosed = true;

      Connection connection;
      while ((connection = connectionPool.poll()) != null)
      {
        connection.close();
      }

      factory.close();

      // Since we have the exclusive lock, there should be no more connections
      // in use.
      if (availableConnections.availablePermits() != poolSize)
      {
        throw new IllegalStateException(
            "Pool has remaining connections open after close");
      }
    }

    @Override
    public Connection getConnection() throws DirectoryException
    {
      // This should only be called with the policy's shared lock.
      if (poolIsClosed)
      {
        throw new IllegalStateException("pool is closed");
      }

      availableConnections.acquireUninterruptibly();

      // There is either a pooled connection or we are allowed to create
      // one.
      Connection connection = connectionPool.poll();
      if (connection == null)
      {
        try
        {
          connection = factory.getConnection();
        }
        catch (final DirectoryException e)
        {
          availableConnections.release();
          throw e;
        }
      }

      return new PooledConnection(connection);
    }
  }

  /**
   * A simplistic two-way fail-over connection factory implementation.
   * <p>
   * Package private for testing.
   */
  static final class FailoverLoadBalancer extends AbstractLoadBalancer
  {
    /**
     * Creates a new fail-over connection factory which will always try the
     * primary connection factory first, before trying the second.
     *
     * @param primary
     *          The primary connection factory.
     * @param secondary
     *          The secondary connection factory.
     * @param scheduler
     *          The monitoring scheduler.
     */
    FailoverLoadBalancer(final ConnectionFactory primary,
        final ConnectionFactory secondary,
        final ScheduledExecutorService scheduler)
    {
      super(new ConnectionFactory[] { primary, secondary }, scheduler);
    }

    @Override
    int getStartIndex()
    {
      // Always start with the primaries.
      return 0;
    }
  }

  /**
   * The PTA design guarantees that connections are only used by a single thread
   * at a time, so we do not need to perform any synchronization.
   * <p>
   * Package private for testing.
   */
  static final class LDAPConnectionFactory implements ConnectionFactory
  {
    /** LDAP connection implementation. */
    private final class LDAPConnection implements Connection
    {
      private final Socket plainSocket;
      private final Socket ldapSocket;
      private final LDAPWriter writer;
      private final LDAPReader reader;
      private int nextMessageID = 1;
      private boolean isClosed;

      private LDAPConnection(final Socket plainSocket, final Socket ldapSocket,
          final LDAPReader reader, final LDAPWriter writer)
      {
        this.plainSocket = plainSocket;
        this.ldapSocket = ldapSocket;
        this.reader = reader;
        this.writer = writer;
      }

      @Override
      public void close()
      {
        /*
         * This method is intentionally a bit "belt and braces" because we have
         * seen far too many subtle resource leaks due to bugs within JDK,
         * especially when used in conjunction with SSL (e.g.
         * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7025227).
         */
        if (isClosed)
        {
          return;
        }
        isClosed = true;

        // Send an unbind request.
        final LDAPMessage message = new LDAPMessage(nextMessageID++,
            new UnbindRequestProtocolOp());
        try
        {
          writer.writeMessage(message);
        }
        catch (final IOException e)
        {
          logger.traceException(e);
        }

        // Close all IO resources.
        StaticUtils.close(writer, reader);
        StaticUtils.close(ldapSocket, plainSocket);
      }

      @Override
      public ByteString search(final DN baseDN, final SearchScope scope,
          final SearchFilter filter) throws DirectoryException
      {
        // Create the search request and send it to the server.
        final SearchRequestProtocolOp searchRequest =
          new SearchRequestProtocolOp(
            ByteString.valueOfUtf8(baseDN.toString()), scope,
            DereferenceAliasesPolicy.ALWAYS, 1 /* size limit */,
            (timeoutMS / 1000), true /* types only */,
            RawFilter.create(filter), NO_ATTRIBUTES);
        sendRequest(searchRequest);

        // Read the responses from the server. We cannot fail-fast since this
        // could leave unread search response messages.
        byte opType;
        ByteString username = null;
        int resultCount = 0;

        do
        {
          final LDAPMessage responseMessage = readResponse();
          opType = responseMessage.getProtocolOpType();

          switch (opType)
          {
          case OP_TYPE_SEARCH_RESULT_ENTRY:
            final SearchResultEntryProtocolOp searchEntry = responseMessage
                .getSearchResultEntryProtocolOp();
            if (username == null)
            {
              username = ByteString.valueOfUtf8(searchEntry.getDN().toString());
            }
            resultCount++;
            break;

          case OP_TYPE_SEARCH_RESULT_REFERENCE:
            // The reference does not necessarily mean that there would have
            // been any matching results, so lets ignore it.
            break;

          case OP_TYPE_SEARCH_RESULT_DONE:
            final SearchResultDoneProtocolOp searchResult = responseMessage
                .getSearchResultDoneProtocolOp();

            final ResultCode resultCode = ResultCode.valueOf(searchResult
                .getResultCode());
            switch (resultCode.asEnum())
            {
            case SUCCESS:
              // The search succeeded. Drop out of the loop and check that we
              // got a matching entry.
              break;

            case SIZE_LIMIT_EXCEEDED:
              // Multiple matching candidates.
              throw new DirectoryException(
                  ResultCode.CLIENT_SIDE_UNEXPECTED_RESULTS_RETURNED,
                  ERR_LDAP_PTA_CONNECTION_SEARCH_SIZE_LIMIT.get(host, port, cfg.dn(), baseDN, filter));

            default:
              // The search failed for some reason.
              throw new DirectoryException(resultCode,
                  ERR_LDAP_PTA_CONNECTION_SEARCH_FAILED.get(host, port,
                      cfg.dn(), baseDN, filter, resultCode.intValue(),
                      resultCode.getName(), searchResult.getErrorMessage()));
            }

            break;

          default:
            // Check for disconnect notifications.
            handleUnexpectedResponse(responseMessage);
            break;
          }
        }
        while (opType != OP_TYPE_SEARCH_RESULT_DONE);

        if (resultCount > 1)
        {
          // Multiple matching candidates.
          throw new DirectoryException(
              ResultCode.CLIENT_SIDE_UNEXPECTED_RESULTS_RETURNED,
              ERR_LDAP_PTA_CONNECTION_SEARCH_SIZE_LIMIT.get(host, port,
                  cfg.dn(), baseDN, filter));
        }

        if (username == null)
        {
          // No matching entries found.
          throw new DirectoryException(
              ResultCode.CLIENT_SIDE_NO_RESULTS_RETURNED,
              ERR_LDAP_PTA_CONNECTION_SEARCH_NO_MATCHES.get(host, port,
                  cfg.dn(), baseDN, filter));
        }

        return username;
      }

      @Override
      public void simpleBind(final ByteString username,
          final ByteString password) throws DirectoryException
      {
        // Create the bind request and send it to the server.
        final BindRequestProtocolOp bindRequest = new BindRequestProtocolOp(
            username, 3, password);
        sendRequest(bindRequest);

        // Read the response from the server.
        final LDAPMessage responseMessage = readResponse();
        switch (responseMessage.getProtocolOpType())
        {
        case OP_TYPE_BIND_RESPONSE:
          final BindResponseProtocolOp bindResponse = responseMessage
              .getBindResponseProtocolOp();

          final ResultCode resultCode = ResultCode.valueOf(bindResponse
              .getResultCode());
          if (resultCode == ResultCode.SUCCESS)
          {
            // FIXME: need to look for things like password expiration
            // warning, reset notice, etc.
            return;
          }
          else
          {
            // The bind failed for some reason.
            throw new DirectoryException(resultCode,
                ERR_LDAP_PTA_CONNECTION_BIND_FAILED.get(host, port,
                    cfg.dn(), username,
                    resultCode.intValue(), resultCode.getName(),
                    bindResponse.getErrorMessage()));
          }

        default:
          // Check for disconnect notifications.
          handleUnexpectedResponse(responseMessage);
          break;
        }
      }

      @Override
      protected void finalize()
      {
        close();
      }

      private void handleUnexpectedResponse(final LDAPMessage responseMessage)
          throws DirectoryException
      {
        if (responseMessage.getProtocolOpType() == OP_TYPE_EXTENDED_RESPONSE)
        {
          final ExtendedResponseProtocolOp extendedResponse = responseMessage
              .getExtendedResponseProtocolOp();
          final String responseOID = extendedResponse.getOID();

          if (OID_NOTICE_OF_DISCONNECTION.equals(responseOID))
          {
            ResultCode resultCode = ResultCode.valueOf(extendedResponse.getResultCode());

            /*
             * Since the connection has been disconnected we want to ensure that
             * upper layers treat all disconnect notifications as fatal and
             * close the connection. Therefore we map the result code to a fatal
             * error code if needed. A good example of a non-fatal error code
             * being returned is INVALID_CREDENTIALS which is used to indicate
             * that the currently bound user has had their entry removed. We
             * definitely don't want to pass this straight back to the caller
             * since it will be misinterpreted as an authentication failure if
             * the operation being performed is a bind.
             */
            ResultCode mappedResultCode = isServiceError(resultCode) ?
                resultCode : ResultCode.UNAVAILABLE;

            throw new DirectoryException(mappedResultCode,
                ERR_LDAP_PTA_CONNECTION_DISCONNECTING.get(host, port,
                    cfg.dn(), resultCode.intValue(), resultCode.getName(),
                    extendedResponse.getErrorMessage()));
          }
        }

        // Unexpected response type.
        throw new DirectoryException(ResultCode.CLIENT_SIDE_DECODING_ERROR,
            ERR_LDAP_PTA_CONNECTION_WRONG_RESPONSE.get(host, port,
                cfg.dn(), responseMessage.getProtocolOp()));
      }

      /** Reads a response message and adapts errors to directory exceptions. */
      private LDAPMessage readResponse() throws DirectoryException
      {
        final LDAPMessage responseMessage;
        try
        {
          responseMessage = reader.readMessage();
        }
        catch (final DecodeException e)
        {
          // ASN1 layer hides all underlying IO exceptions.
          if (e.getCause() instanceof SocketTimeoutException)
          {
            throw new DirectoryException(ResultCode.CLIENT_SIDE_TIMEOUT,
                ERR_LDAP_PTA_CONNECTION_TIMEOUT.get(host, port, cfg.dn()), e);
          }
          else if (e.getCause() instanceof IOException)
          {
            throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
                ERR_LDAP_PTA_CONNECTION_OTHER_ERROR.get(host, port, cfg.dn(), e.getMessage()), e);
          }
          else
          {
            throw new DirectoryException(ResultCode.CLIENT_SIDE_DECODING_ERROR,
                ERR_LDAP_PTA_CONNECTION_DECODE_ERROR.get(host, port, cfg.dn(), e.getMessage()), e);
          }
        }
        catch (final LDAPException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_DECODING_ERROR,
              ERR_LDAP_PTA_CONNECTION_DECODE_ERROR.get(host, port,
                  cfg.dn(), e.getMessage()), e);
        }
        catch (final SocketTimeoutException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_TIMEOUT,
              ERR_LDAP_PTA_CONNECTION_TIMEOUT.get(host, port, cfg.dn()), e);
        }
        catch (final IOException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
              ERR_LDAP_PTA_CONNECTION_OTHER_ERROR.get(host, port, cfg.dn(), e.getMessage()), e);
        }

        if (responseMessage == null)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
              ERR_LDAP_PTA_CONNECTION_CLOSED.get(host, port, cfg.dn()));
        }
        return responseMessage;
      }

      /** Sends a request message and adapts errors to directory exceptions. */
      private void sendRequest(final ProtocolOp request)
          throws DirectoryException
      {
        final LDAPMessage requestMessage = new LDAPMessage(nextMessageID++,
            request);
        try
        {
          writer.writeMessage(requestMessage);
        }
        catch (final IOException e)
        {
          throw new DirectoryException(ResultCode.CLIENT_SIDE_SERVER_DOWN,
              ERR_LDAP_PTA_CONNECTION_OTHER_ERROR.get(host, port, cfg.dn(), e.getMessage()), e);
        }
      }
    }

    private final String host;
    private final int port;
    private final LDAPPassThroughAuthenticationPolicyCfg cfg;
    private final int timeoutMS;

    /**
     * LDAP connection factory implementation is package private so that it can
     * be tested.
     *
     * @param host
     *          The server host name.
     * @param port
     *          The server port.
     * @param cfg
     *          The configuration (for SSL).
     */
    LDAPConnectionFactory(final String host, final int port,
        final LDAPPassThroughAuthenticationPolicyCfg cfg)
    {
      this.host = host;
      this.port = port;
      this.cfg = cfg;

      // Normalize the timeoutMS to an integer (admin framework ensures that the
      // value is non-negative).
      this.timeoutMS = (int) Math.min(cfg.getConnectionTimeout(),
          Integer.MAX_VALUE);
    }

    @Override
    public void close()
    {
      // Nothing to do.
    }

    @Override
    public Connection getConnection() throws DirectoryException
    {
      try
      {
        // Create the remote ldapSocket address.
        final InetAddress address = InetAddress.getByName(host);
        final InetSocketAddress socketAddress = new InetSocketAddress(address,
            port);

        // Create the ldapSocket and connect to the remote server.
        final Socket plainSocket = new Socket();
        Socket ldapSocket = null;
        LDAPReader reader = null;
        LDAPWriter writer = null;
        LDAPConnection ldapConnection = null;

        try
        {
          // Set ldapSocket cfg before connecting.
          plainSocket.setReuseAddress(true);
          plainSocket.setTcpNoDelay(cfg.isUseTCPNoDelay());
          plainSocket.setKeepAlive(cfg.isUseTCPKeepAlive());
          plainSocket.setSoTimeout(timeoutMS);
          if (cfg.getSourceAddress() != null)
          {
            InetSocketAddress local = new InetSocketAddress(cfg.getSourceAddress(), 0);
            plainSocket.bind(local);
          }
          // Connect the ldapSocket.
          plainSocket.connect(socketAddress, timeoutMS);

          if (cfg.isUseSSL())
          {
            // Obtain the optional configured trust manager which will be used
            // in order to determine the trust of the remote LDAP server.
            TrustManager[] tm = null;
            final DN trustManagerDN = cfg.getTrustManagerProviderDN();
            if (trustManagerDN != null)
            {
              final TrustManagerProvider<?> trustManagerProvider =
                DirectoryServer.getTrustManagerProvider(trustManagerDN);
              if (trustManagerProvider != null)
              {
                tm = trustManagerProvider.getTrustManagers();
              }
            }

            // Create the SSL context and initialize it.
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null /* key managers */, tm, null /* rng */);

            // Create the SSL socket.
            final SSLSocketFactory sslSocketFactory = sslContext
                .getSocketFactory();
            final SSLSocket sslSocket = (SSLSocket) sslSocketFactory
                .createSocket(plainSocket, host, port, true);
            ldapSocket = sslSocket;

            sslSocket.setUseClientMode(true);
            if (!cfg.getSSLProtocol().isEmpty())
            {
              sslSocket.setEnabledProtocols(cfg.getSSLProtocol().toArray(
                  new String[0]));
            }
            if (!cfg.getSSLCipherSuite().isEmpty())
            {
              sslSocket.setEnabledCipherSuites(cfg.getSSLCipherSuite().toArray(
                  new String[0]));
            }

            // Force TLS negotiation.
            sslSocket.startHandshake();
          }
          else
          {
            ldapSocket = plainSocket;
          }

          reader = new LDAPReader(ldapSocket);
          writer = new LDAPWriter(ldapSocket);

          ldapConnection = new LDAPConnection(plainSocket, ldapSocket, reader,
              writer);

          return ldapConnection;
        }
        finally
        {
          if (ldapConnection == null)
          {
            // Connection creation failed for some reason, so clean up IO
            // resources.
            StaticUtils.close(reader, writer);
            StaticUtils.close(ldapSocket);

            if (ldapSocket != plainSocket)
            {
              StaticUtils.close(plainSocket);
            }
          }
        }
      }
      catch (final UnknownHostException e)
      {
        logger.traceException(e);
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_UNKNOWN_HOST.get(host, port, cfg.dn(), host), e);
      }
      catch (final ConnectException e)
      {
        logger.traceException(e);
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_ERROR.get(host, port, cfg.dn(), port), e);
      }
      catch (final SocketTimeoutException e)
      {
        logger.traceException(e);
        throw new DirectoryException(ResultCode.CLIENT_SIDE_TIMEOUT,
            ERR_LDAP_PTA_CONNECT_TIMEOUT.get(host, port, cfg.dn()), e);
      }
      catch (final SSLException e)
      {
        logger.traceException(e);
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_SSL_ERROR.get(host, port, cfg.dn(), e.getMessage()), e);
      }
      catch (final Exception e)
      {
        logger.traceException(e);
        throw new DirectoryException(ResultCode.CLIENT_SIDE_CONNECT_ERROR,
            ERR_LDAP_PTA_CONNECT_OTHER_ERROR.get(host, port, cfg.dn(), e.getMessage()), e);
      }
    }
  }

  /**
   * An interface for obtaining a connection factory for LDAP connections to a
   * named LDAP server and the monitoring scheduler.
   */
  static interface Provider
  {
    /**
     * Returns a connection factory which can be used for obtaining connections
     * to the specified LDAP server.
     *
     * @param host
     *          The LDAP server host name.
     * @param port
     *          The LDAP server port.
     * @param cfg
     *          The LDAP connection configuration.
     * @return A connection factory which can be used for obtaining connections
     *         to the specified LDAP server.
     */
    ConnectionFactory getLDAPConnectionFactory(String host, int port,
        LDAPPassThroughAuthenticationPolicyCfg cfg);

    /**
     * Returns the scheduler which should be used to periodically ping
     * connection factories to determine when they are online.
     *
     * @return The scheduler which should be used to periodically ping
     *         connection factories to determine when they are online.
     */
    ScheduledExecutorService getScheduledExecutorService();

    /**
     * Returns the current time in order to perform cached password expiration
     * checks. The returned string will be formatted as a a generalized time
     * string
     *
     * @return The current time.
     */
    String getCurrentTime();

    /**
     * Returns the current time in order to perform cached password expiration
     * checks.
     *
     * @return The current time in MS.
     */
    long getCurrentTimeMS();
  }

  /**
   * A simplistic load-balancer connection factory implementation using
   * approximately round-robin balancing.
   */
  static final class RoundRobinLoadBalancer extends AbstractLoadBalancer
  {
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final int maxIndex;

    /**
     * Creates a new load-balancer which will distribute connection requests
     * across a set of underlying connection factories.
     *
     * @param factories
     *          The list of underlying connection factories.
     * @param scheduler
     *          The monitoring scheduler.
     */
    RoundRobinLoadBalancer(final ConnectionFactory[] factories,
        final ScheduledExecutorService scheduler)
    {
      super(factories, scheduler);
      this.maxIndex = factories.length;
    }

    @Override
    int getStartIndex()
    {
      // A round robin pool of one connection factories is unlikely in
      // practice and requires special treatment.
      if (maxIndex == 1)
      {
        return 0;
      }

      // Determine the next factory to use: avoid blocking algorithm.
      int oldNextIndex;
      int newNextIndex;
      do
      {
        oldNextIndex = nextIndex.get();
        newNextIndex = oldNextIndex + 1;
        if (newNextIndex == maxIndex)
        {
          newNextIndex = 0;
        }
      }
      while (!nextIndex.compareAndSet(oldNextIndex, newNextIndex));

      // There's a potential, but benign, race condition here: other threads
      // could jump in and rotate through the list before we return the
      // connection factory.
      return oldNextIndex;
    }
  }

  /** LDAP PTA policy implementation. */
  private final class PolicyImpl extends AuthenticationPolicy implements
      ConfigurationChangeListener<LDAPPassThroughAuthenticationPolicyCfg>
  {
    /** LDAP PTA policy state implementation. */
    private final class StateImpl extends AuthenticationPolicyState
    {
      private final AttributeType cachedPasswordAttribute;
      private final AttributeType cachedPasswordTimeAttribute;

      private ByteString newCachedPassword;

      private StateImpl(final Entry userEntry)
      {
        super(userEntry);

        Schema schema = DirectoryServer.getInstance().getServerContext().getSchema();
        this.cachedPasswordAttribute = schema.getAttributeType(OP_ATTR_PTAPOLICY_CACHED_PASSWORD);
        this.cachedPasswordTimeAttribute = schema.getAttributeType(OP_ATTR_PTAPOLICY_CACHED_PASSWORD_TIME);
      }

      @Override
      public void finalizeStateAfterBind() throws DirectoryException
      {
        sharedLock.lock();
        try
        {
          if (cfg.isUsePasswordCaching() && newCachedPassword != null)
          {
            // Update the user's entry to contain the cached password and
            // time stamp.
            ByteString encodedPassword = pwdStorageScheme
                .encodePasswordWithScheme(newCachedPassword);

            List<RawModification> modifications = new ArrayList<>(2);
            modifications.add(RawModification.create(ModificationType.REPLACE,
                OP_ATTR_PTAPOLICY_CACHED_PASSWORD, encodedPassword));
            modifications.add(RawModification.create(ModificationType.REPLACE,
                OP_ATTR_PTAPOLICY_CACHED_PASSWORD_TIME,
                provider.getCurrentTime()));

            ModifyOperation internalModify = getRootConnection().processModify(
                ByteString.valueOfObject(userEntry.getName()), modifications);

            ResultCode resultCode = internalModify.getResultCode();
            if (resultCode != ResultCode.SUCCESS)
            {
              // The modification failed for some reason. This should not
              // prevent the bind from succeeded since we are only updating
              // cache data. However, the performance of the server may be
              // impacted, so log a debug warning message.
              if (logger.isTraceEnabled())
              {
                logger.trace(
                    "An error occurred while trying to update the LDAP PTA "
                        + "cached password for user %s: %s",
                        userEntry.getName(), internalModify.getErrorMessage());
              }
            }

            newCachedPassword = null;
          }
        }
        finally
        {
          sharedLock.unlock();
        }
      }

      @Override
      public AuthenticationPolicy getAuthenticationPolicy()
      {
        return PolicyImpl.this;
      }

      @Override
      public boolean passwordMatches(final ByteString password)
          throws DirectoryException
      {
        sharedLock.lock();
        try
        {
          // First check the cached password if enabled and available.
          if (passwordMatchesCachedPassword(password))
          {
            return true;
          }

          // The cache lookup failed, so perform full PTA.
          ByteString username = null;

          switch (cfg.getMappingPolicy())
          {
          case UNMAPPED:
            // The bind DN is the name of the user's entry.
            username = ByteString.valueOfUtf8(userEntry.getName().toString());
            break;
          case MAPPED_BIND:
            // The bind DN is contained in an attribute in the user's entry.
            mapBind: for (final AttributeType at : cfg.getMappedAttribute())
            {
              for (final Attribute attribute : userEntry.getAllAttributes(at))
              {
                if (!attribute.isEmpty())
                {
                  username = attribute.iterator().next();
                  break mapBind;
                }
              }
            }

            if (username == null)
            {
              /*
               * The mapping attribute(s) is not present in the entry. This
               * could be a configuration error, but it could also be because
               * someone is attempting to authenticate using a bind DN which
               * references a non-user entry.
               */
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPING_ATTRIBUTE_NOT_FOUND.get(
                      userEntry.getName(), cfg.dn(),
                      mappedAttributesAsString(cfg.getMappedAttribute())));
            }

            break;
          case MAPPED_SEARCH:
            // A search against the remote directory is required in order to
            // determine the bind DN.

            final String filterTemplate =  cfg.getMappedSearchFilterTemplate();

            // Construct the search filter.
            final LinkedList<SearchFilter> filterComponents = new LinkedList<>();
            for (final AttributeType at : cfg.getMappedAttribute())
            {
              for (final Attribute attribute : userEntry.getAllAttributes(at))
              {
                for (final ByteString value : attribute)
                {
                  if (filterTemplate != null)
                  {
                    filterComponents.add(SearchFilter.createFilterFromString(
                        Filter.format(filterTemplate, value).toString()));
                  }
                  else
                  {
                    filterComponents.add(SearchFilter.createEqualityFilter(at, value));
                  }
                }
              }
            }

            if (filterComponents.isEmpty())
            {
              /*
               * The mapping attribute(s) is not present in the entry. This
               * could be a configuration error, but it could also be because
               * someone is attempting to authenticate using a bind DN which
               * references a non-user entry.
               */
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPING_ATTRIBUTE_NOT_FOUND.get(
                      userEntry.getName(), cfg.dn(),
                      mappedAttributesAsString(cfg.getMappedAttribute())));
            }

            final SearchFilter filter;
            if (filterComponents.size() == 1)
            {
              filter = filterComponents.getFirst();
            }
            else
            {
              filter = SearchFilter.createORFilter(filterComponents);
            }

            // Now search the configured base DNs, stopping at the first
            // success.
            for (final DN baseDN : cfg.getMappedSearchBaseDN())
            {
              Connection connection = null;
              try
              {
                connection = searchFactory.getConnection();
                username = connection.search(baseDN, SearchScope.WHOLE_SUBTREE,
                    filter);
              }
              catch (final DirectoryException e)
              {
                switch (e.getResultCode().asEnum())
                {
                case NO_SUCH_OBJECT:
                case CLIENT_SIDE_NO_RESULTS_RETURNED:
                  // Ignore and try next base DN.
                  break;
                case CLIENT_SIDE_UNEXPECTED_RESULTS_RETURNED:
                  // More than one matching entry was returned.
                  throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                      ERR_LDAP_PTA_MAPPED_SEARCH_TOO_MANY_CANDIDATES.get(
                          userEntry.getName(), cfg.dn(), baseDN, filter));
                default:
                  // We don't want to propagate this internal error to the
                  // client. We should log it and map it to a more appropriate
                  // error.
                  throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                      ERR_LDAP_PTA_MAPPED_SEARCH_FAILED.get(
                          userEntry.getName(), cfg.dn(), e.getMessageObject()), e);
                }
              }
              finally
              {
                StaticUtils.close(connection);
              }
            }

            if (username == null)
            {
              /* No matching entries were found in the remote directory. */
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPED_SEARCH_NO_CANDIDATES.get(
                      userEntry.getName(), cfg.dn(), filter));
            }

            break;
          }

          // Now perform the bind.
          try (Connection connection = bindFactory.getConnection())
          {
            connection.simpleBind(username, password);

            // The password matched, so cache it, it will be stored in the
            // user's entry when the state is finalized and only if caching is
            // enabled.
            newCachedPassword = password;
            return true;
          }
          catch (final DirectoryException e)
          {
            switch (e.getResultCode().asEnum())
            {
            case NO_SUCH_OBJECT:
            case INVALID_CREDENTIALS:
              return false;
            default:
              // We don't want to propagate this internal error to the
              // client. We should log it and map it to a more appropriate
              // error.
              throw new DirectoryException(ResultCode.INVALID_CREDENTIALS,
                  ERR_LDAP_PTA_MAPPED_BIND_FAILED.get(
                      userEntry.getName(), cfg.dn(), e.getMessageObject()), e);
            }
          }
        }
        finally
        {
          sharedLock.unlock();
        }
      }

      private boolean passwordMatchesCachedPassword(ByteString password)
      {
        if (!cfg.isUsePasswordCaching())
        {
          return false;
        }

        // First determine if the cached password time is present and valid.
        boolean foundValidCachedPasswordTime = false;

        foundCachedPasswordTime:
        for (Attribute attribute : userEntry.getAllAttributes(cachedPasswordTimeAttribute))
        {
          // Ignore any attributes with options.
          if (!attribute.getAttributeDescription().hasOptions())
          {
            for (ByteString value : attribute)
            {
              try
              {
                long cachedPasswordTime = GeneralizedTime.valueOf(value.toString()).getTimeInMillis();
                long currentTime = provider.getCurrentTimeMS();
                long expiryTime = cachedPasswordTime + (cfg.getCachedPasswordTTL() * 1000);
                foundValidCachedPasswordTime = expiryTime > currentTime;
              }
              catch (LocalizedIllegalArgumentException e)
              {
                // Fall-through and give up immediately.
                logger.traceException(e);
              }
              break foundCachedPasswordTime;
            }
          }
        }

        if (!foundValidCachedPasswordTime)
        {
          // The cached password time was not found or it has expired, so give
          // up immediately.
          return false;
        }

        // Next determine if there is a cached password.
        ByteString cachedPassword = null;
        foundCachedPassword:
        for (Attribute attribute : userEntry.getAllAttributes(cachedPasswordAttribute))
        {
          // Ignore any attributes with options.
          if (!attribute.getAttributeDescription().hasOptions())
          {
            for (ByteString value : attribute)
            {
              cachedPassword = value;
              break foundCachedPassword;
            }
          }
        }

        if (cachedPassword == null)
        {
          // The cached password was not found, so give up immediately.
          return false;
        }

        // Decode the password and match it according to its storage scheme.
        try
        {
          String[] userPwComponents = UserPasswordSyntax
              .decodeUserPassword(cachedPassword.toString());
          PasswordStorageScheme<?> scheme = DirectoryServer
              .getPasswordStorageScheme(userPwComponents[0]);
          if (scheme != null)
          {
            return scheme.passwordMatches(password,
                ByteString.valueOfUtf8(userPwComponents[1]));
          }
        }
        catch (DirectoryException e)
        {
          // Unable to decode the cached password, so give up.
          logger.traceException(e);
        }

        return false;
      }
    }

    /** Guards against configuration changes. */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReadLock sharedLock = lock.readLock();
    private final WriteLock exclusiveLock = lock.writeLock();

    /** Current configuration. */
    private LDAPPassThroughAuthenticationPolicyCfg cfg;

    private ConnectionFactory searchFactory;
    private ConnectionFactory bindFactory;

    private PasswordStorageScheme<?> pwdStorageScheme;

    private PolicyImpl(
        final LDAPPassThroughAuthenticationPolicyCfg configuration)
    {
      initializeConfiguration(configuration);
    }

    @Override
    public ConfigChangeResult applyConfigurationChange(
        final LDAPPassThroughAuthenticationPolicyCfg cfg)
    {
      exclusiveLock.lock();
      try
      {
        closeConnections();
        initializeConfiguration(cfg);
      }
      finally
      {
        exclusiveLock.unlock();
      }
      return new ConfigChangeResult();
    }

    @Override
    public AuthenticationPolicyState createAuthenticationPolicyState(
        final Entry userEntry, final long time) throws DirectoryException
    {
      // The current time is not needed for LDAP PTA.
      return new StateImpl(userEntry);
    }

    @Override
    public void finalizeAuthenticationPolicy()
    {
      exclusiveLock.lock();
      try
      {
        cfg.removeLDAPPassThroughChangeListener(this);
        closeConnections();
      }
      finally
      {
        exclusiveLock.unlock();
      }
    }

    @Override
    public DN getDN()
    {
      return cfg.dn();
    }

    @Override
    public boolean isConfigurationChangeAcceptable(
        final LDAPPassThroughAuthenticationPolicyCfg cfg,
        final List<LocalizableMessage> unacceptableReasons)
    {
      return LDAPPassThroughAuthenticationPolicyFactory.this
          .isConfigurationAcceptable(cfg, unacceptableReasons);
    }

    private void closeConnections()
    {
      exclusiveLock.lock();
      try
      {
        if (searchFactory != null)
        {
          searchFactory.close();
          searchFactory = null;
        }

        if (bindFactory != null)
        {
          bindFactory.close();
          bindFactory = null;
        }
      }
      finally
      {
        exclusiveLock.unlock();
      }
    }

    private void initializeConfiguration(
        final LDAPPassThroughAuthenticationPolicyCfg cfg)
    {
      this.cfg = cfg;

      // First obtain the mapped search password if needed, ignoring any errors
      // since these should have already been detected during configuration
      // validation.
      final String mappedSearchPassword;
      if (cfg.getMappingPolicy() == MappingPolicy.MAPPED_SEARCH
          && cfg.getMappedSearchBindDN() != null
          && !cfg.getMappedSearchBindDN().isRootDN())
      {
        mappedSearchPassword = getMappedSearchBindPassword(cfg,
            new LinkedList<LocalizableMessage>());
      }
      else
      {
        mappedSearchPassword = null;
      }

      // Use two pools per server: one for authentication (bind) and one for
      // searches. Even if the searches are performed anonymously we cannot use
      // the same pool, otherwise they will be performed as the most recently
      // authenticated user.

      // Create load-balancers for primary servers.
      final RoundRobinLoadBalancer primarySearchLoadBalancer;
      final RoundRobinLoadBalancer primaryBindLoadBalancer;
      final ScheduledExecutorService scheduler = provider
          .getScheduledExecutorService();

      Set<String> servers = cfg.getPrimaryRemoteLDAPServer();
      ConnectionPool[] searchPool = new ConnectionPool[servers.size()];
      ConnectionPool[] bindPool = new ConnectionPool[servers.size()];
      int index = 0;
      for (final String hostPort : servers)
      {
        final ConnectionFactory factory = newLDAPConnectionFactory(hostPort);
        searchPool[index] = new ConnectionPool(
            new AuthenticatedConnectionFactory(factory,
                cfg.getMappedSearchBindDN(),
                mappedSearchPassword));
        bindPool[index++] = new ConnectionPool(factory);
      }
      primarySearchLoadBalancer = new RoundRobinLoadBalancer(searchPool,
          scheduler);
      primaryBindLoadBalancer = new RoundRobinLoadBalancer(bindPool, scheduler);

      // Create load-balancers for secondary servers.
      servers = cfg.getSecondaryRemoteLDAPServer();
      if (servers.isEmpty())
      {
        searchFactory = primarySearchLoadBalancer;
        bindFactory = primaryBindLoadBalancer;
      }
      else
      {
        searchPool = new ConnectionPool[servers.size()];
        bindPool = new ConnectionPool[servers.size()];
        index = 0;
        for (final String hostPort : servers)
        {
          final ConnectionFactory factory = newLDAPConnectionFactory(hostPort);
          searchPool[index] = new ConnectionPool(
              new AuthenticatedConnectionFactory(factory,
                  cfg.getMappedSearchBindDN(),
                  mappedSearchPassword));
          bindPool[index++] = new ConnectionPool(factory);
        }
        final RoundRobinLoadBalancer secondarySearchLoadBalancer =
          new RoundRobinLoadBalancer(searchPool, scheduler);
        final RoundRobinLoadBalancer secondaryBindLoadBalancer =
          new RoundRobinLoadBalancer(bindPool, scheduler);
        searchFactory = new FailoverLoadBalancer(primarySearchLoadBalancer,
            secondarySearchLoadBalancer, scheduler);
        bindFactory = new FailoverLoadBalancer(primaryBindLoadBalancer,
            secondaryBindLoadBalancer, scheduler);
      }

      if (cfg.isUsePasswordCaching())
      {
        pwdStorageScheme = DirectoryServer.getPasswordStorageScheme(cfg
            .getCachedPasswordStorageSchemeDN());
      }
    }

    private ConnectionFactory newLDAPConnectionFactory(final String hostPort)
    {
      // Validation already performed by admin framework.
      final HostPort hp = HostPort.valueOf(hostPort);
      return provider.getLDAPConnectionFactory(hp.getHost(), hp.getPort(), cfg);
    }
  }

  /** Debug tracer for this class. */
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /** Attribute list for searches requesting no attributes. */
  static final LinkedHashSet<String> NO_ATTRIBUTES = new LinkedHashSet<>(1);
  static
  {
    NO_ATTRIBUTES.add(SchemaConstants.NO_ATTRIBUTES);
  }

  /** The provider which should be used by policies to create LDAP connections. */
  private final Provider provider;

  private ServerContext serverContext;

  /** The default LDAP connection factory provider. */
  private static final Provider DEFAULT_PROVIDER = new Provider()
  {

    /**
     * Global scheduler used for periodically monitoring connection factories in
     * order to detect when they are online.
     */
    private final ScheduledExecutorService scheduler = Executors
        .newScheduledThreadPool(2, new ThreadFactory()
        {

          @Override
          public Thread newThread(final Runnable r)
          {
            final Thread t = new DirectoryThread(r,
                "LDAP PTA connection monitor thread");
            t.setDaemon(true);
            return t;
          }
        });

    @Override
    public ConnectionFactory getLDAPConnectionFactory(final String host,
        final int port, final LDAPPassThroughAuthenticationPolicyCfg cfg)
    {
      return new LDAPConnectionFactory(host, port, cfg);
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService()
    {
      return scheduler;
    }

    @Override
    public String getCurrentTime()
    {
      return TimeThread.getGMTTime();
    }

    @Override
    public long getCurrentTimeMS()
    {
      return TimeThread.getTime();
    }

  };

  /**
   * Determines whether or no a result code is expected to trigger the
   * associated connection to be closed immediately.
   *
   * @param resultCode
   *          The result code.
   * @return {@code true} if the result code is expected to trigger the
   *         associated connection to be closed immediately.
   */
  static boolean isServiceError(final ResultCode resultCode)
  {
    switch (resultCode.asEnum())
    {
    case OPERATIONS_ERROR:
    case PROTOCOL_ERROR:
    case TIME_LIMIT_EXCEEDED:
    case ADMIN_LIMIT_EXCEEDED:
    case UNAVAILABLE_CRITICAL_EXTENSION:
    case BUSY:
    case UNAVAILABLE:
    case UNWILLING_TO_PERFORM:
    case LOOP_DETECT:
    case OTHER:
    case CLIENT_SIDE_CONNECT_ERROR:
    case CLIENT_SIDE_DECODING_ERROR:
    case CLIENT_SIDE_ENCODING_ERROR:
    case CLIENT_SIDE_LOCAL_ERROR:
    case CLIENT_SIDE_SERVER_DOWN:
    case CLIENT_SIDE_TIMEOUT:
      return true;
    default:
      return false;
    }
  }

  /**
   * Get the search bind password performing mapped searches.
   * We will offer several places to look for the password, and we will
   * do so in the following order:
   * - In a specified Java property
   * - In a specified environment variable
   * - In a specified file on the server filesystem.
   * - As the value of a configuration attribute.
   * In any case, the password must be in the clear.
   */
  private static String getMappedSearchBindPassword(
      final LDAPPassThroughAuthenticationPolicyCfg cfg,
      final List<LocalizableMessage> unacceptableReasons)
  {
    String password = null;

    if (cfg.getMappedSearchBindPasswordProperty() != null)
    {
      String propertyName = cfg.getMappedSearchBindPasswordProperty();
      password = System.getProperty(propertyName);
      if (password == null)
      {
        unacceptableReasons.add(ERR_LDAP_PTA_PWD_PROPERTY_NOT_SET.get(cfg.dn(), propertyName));
      }
    }
    else if (cfg.getMappedSearchBindPasswordEnvironmentVariable() != null)
    {
      String envVarName = cfg.getMappedSearchBindPasswordEnvironmentVariable();
      password = System.getenv(envVarName);
      if (password == null)
      {
        unacceptableReasons.add(ERR_LDAP_PTA_PWD_ENVAR_NOT_SET.get(cfg.dn(), envVarName));
      }
    }
    else if (cfg.getMappedSearchBindPasswordFile() != null)
    {
      String fileName = cfg.getMappedSearchBindPasswordFile();
      File passwordFile = getFileForPath(fileName);
      if (!passwordFile.exists())
      {
        unacceptableReasons.add(ERR_LDAP_PTA_PWD_NO_SUCH_FILE.get(cfg.dn(), fileName));
      }
      else
      {
        BufferedReader br = null;
        try
        {
          br = new BufferedReader(new FileReader(passwordFile));
          password = br.readLine();
          if (password == null)
          {
            unacceptableReasons.add(ERR_LDAP_PTA_PWD_FILE_EMPTY.get(cfg.dn(), fileName));
          }
        }
        catch (IOException e)
        {
          unacceptableReasons.add(ERR_LDAP_PTA_PWD_FILE_CANNOT_READ.get(
              cfg.dn(), fileName, getExceptionMessage(e)));
        }
        finally
        {
          StaticUtils.close(br);
        }
      }
    }
    else if (cfg.getMappedSearchBindPassword() != null)
    {
      password = cfg.getMappedSearchBindPassword();
    }
    else
    {
      // Password wasn't defined anywhere.
      unacceptableReasons.add(ERR_LDAP_PTA_NO_PWD.get(cfg.dn()));
    }

    return password;
  }

  private static boolean isMappedFilterTemplateValid(
      final String filterTemplate,
      final List<LocalizableMessage> unacceptableReasons)
  {
    if (filterTemplate != null)
    {
      try
      {
        Filter.format(filterTemplate, "testValue");
      }
      catch(IllegalFormatConversionException | MissingFormatArgumentException | LocalizedIllegalArgumentException e)
      {
        unacceptableReasons.add(ERR_LDAP_PTA_INVALID_FILTER_TEMPLATE.get(filterTemplate));
        return false;
      }
    }

    return true;
  }

  private static boolean isServerAddressValid(
      final LDAPPassThroughAuthenticationPolicyCfg configuration,
      final List<LocalizableMessage> unacceptableReasons, final String hostPort)
  {
    try
    {
      // validate provided string
      HostPort.valueOf(hostPort);
      return true;
    }
    catch (RuntimeException e)
    {
      if (unacceptableReasons != null)
      {
        unacceptableReasons.add(ERR_LDAP_PTA_INVALID_PORT_NUMBER.get(configuration.dn(), hostPort));
      }
      return false;
    }
  }

  private static String mappedAttributesAsString(
      final Collection<AttributeType> attributes)
  {
    switch (attributes.size())
    {
    case 0:
      return "";
    case 1:
      return attributes.iterator().next().getNameOrOID();
    default:
      final StringBuilder builder = new StringBuilder();
      final Iterator<AttributeType> i = attributes.iterator();
      builder.append(i.next().getNameOrOID());
      while (i.hasNext())
      {
        builder.append(", ");
        builder.append(i.next().getNameOrOID());
      }
      return builder.toString();
    }
  }

  /**
   * Public default constructor used by the admin framework. This will use the
   * default LDAP connection factory provider.
   */
  public LDAPPassThroughAuthenticationPolicyFactory()
  {
    this(DEFAULT_PROVIDER);
  }

  /**
   * Sets the server context.
   *
   * @param serverContext
   *            The server context.
   */
  @Override
  public void setServerContext(ServerContext serverContext) {
    this.serverContext = serverContext;
  }

  /**
   * Package private constructor allowing unit tests to provide mock connection
   * implementations.
   *
   * @param provider
   *          The LDAP connection factory provider implementation which LDAP PTA
   *          authentication policies will use.
   */
  LDAPPassThroughAuthenticationPolicyFactory(final Provider provider)
  {
    this.provider = provider;
  }

  @Override
  public AuthenticationPolicy createAuthenticationPolicy(
      final LDAPPassThroughAuthenticationPolicyCfg configuration)
      throws ConfigException, InitializationException
  {
    final PolicyImpl policy = new PolicyImpl(configuration);
    configuration.addLDAPPassThroughChangeListener(policy);
    return policy;
  }

  @Override
  public boolean isConfigurationAcceptable(
      final LDAPPassThroughAuthenticationPolicyCfg cfg,
      final List<LocalizableMessage> unacceptableReasons)
  {
    // Check that the port numbers are valid. We won't actually try and connect
    // to the server since they may not be available (hence we have fail-over
    // capabilities).
    boolean configurationIsAcceptable = true;

    for (final String hostPort : cfg.getPrimaryRemoteLDAPServer())
    {
      configurationIsAcceptable &= isServerAddressValid(cfg,
          unacceptableReasons, hostPort);
    }

    for (final String hostPort : cfg.getSecondaryRemoteLDAPServer())
    {
      configurationIsAcceptable &= isServerAddressValid(cfg,
          unacceptableReasons, hostPort);
    }

    // Ensure that the search bind password is defined somewhere.
    if (cfg.getMappingPolicy() == MappingPolicy.MAPPED_SEARCH
        && cfg.getMappedSearchBindDN() != null
        && !cfg.getMappedSearchBindDN().isRootDN()
        && getMappedSearchBindPassword(cfg, unacceptableReasons) == null)
    {
      configurationIsAcceptable = false;
    }

    if (!isMappedFilterTemplateValid(cfg.getMappedSearchFilterTemplate(),
        unacceptableReasons))
    {
      configurationIsAcceptable = false;
    }

    return configurationIsAcceptable;
  }
}
