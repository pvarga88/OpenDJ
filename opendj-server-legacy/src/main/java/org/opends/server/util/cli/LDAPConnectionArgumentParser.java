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
 * Copyright 2008-2010 Sun Microsystems, Inc.
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.server.util.cli;

import static com.forgerock.opendj.cli.Utils.*;

import static org.opends.messages.ToolMessages.*;

import java.io.PrintStream;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import org.forgerock.i18n.LocalizableMessage;
import org.opends.server.admin.client.cli.SecureConnectionCliArgs;
import org.opends.server.core.DirectoryServer.DirectoryServerVersionHandler;
import org.opends.server.tools.LDAPConnection;
import org.opends.server.tools.LDAPConnectionException;
import org.opends.server.tools.LDAPConnectionOptions;
import org.opends.server.tools.SSLConnectionException;
import org.opends.server.tools.SSLConnectionFactory;
import org.opends.server.types.OpenDsException;

import com.forgerock.opendj.cli.Argument;
import com.forgerock.opendj.cli.ArgumentException;
import com.forgerock.opendj.cli.ArgumentGroup;
import com.forgerock.opendj.cli.ArgumentParser;
import com.forgerock.opendj.cli.ClientException;
import com.forgerock.opendj.cli.ConsoleApplication;
import com.forgerock.opendj.cli.FileBasedArgument;
import com.forgerock.opendj.cli.StringArgument;

/**
 * Creates an argument parser pre-populated with arguments for specifying
 * information for opening and LDAPConnection an LDAP connection.
 */
public class LDAPConnectionArgumentParser extends ArgumentParser
{
  private SecureConnectionCliArgs args;

  /**
   * Creates a new instance of this argument parser with no arguments. Unnamed
   * trailing arguments will not be allowed.
   *
   * @param mainClassName
   *          The fully-qualified name of the Java class that should be invoked
   *          to launch the program with which this argument parser is
   *          associated.
   * @param toolDescription
   *          A human-readable description for the tool, which will be included
   *          when displaying usage information.
   * @param longArgumentsCaseSensitive
   *          Indicates whether long arguments should
   * @param argumentGroup
   *          Group to which LDAP arguments will be added to the parser. May be
   *          null to indicate that arguments should be added to the default
   *          group
   * @param alwaysSSL
   *          If true, always use the SSL connection type. In this case, the
   *          arguments useSSL and startTLS are not present.
   */
  public LDAPConnectionArgumentParser(String mainClassName, LocalizableMessage toolDescription,
      boolean longArgumentsCaseSensitive, ArgumentGroup argumentGroup, boolean alwaysSSL)
  {
    super(mainClassName, toolDescription, longArgumentsCaseSensitive);
    addLdapConnectionArguments(argumentGroup, alwaysSSL);
    setVersionHandler(new DirectoryServerVersionHandler());
  }

  /**
   * Creates a new LDAPConnection and invokes a connect operation using
   * information provided in the parsed set of arguments that were provided by
   * the user.
   *
   * @param out
   *          stream to write messages
   * @param err
   *          stream to write error messages
   * @return LDAPConnection created by this class from parsed arguments
   * @throws LDAPConnectionException
   *           if there was a problem connecting to the server indicated by the
   *           input arguments
   * @throws ArgumentException
   *           if there was a problem processing the input arguments
   */
  public LDAPConnection connect(PrintStream out, PrintStream err) throws LDAPConnectionException, ArgumentException
  {
    return connect(this.args, out, err);
  }

  /**
   * Creates a new LDAPConnection and invokes a connect operation using
   * information provided in the parsed set of arguments that were provided by
   * the user.
   *
   * @param args
   *          with which to connect
   * @param out
   *          stream to write messages
   * @param err
   *          stream to write error messages
   * @return LDAPConnection created by this class from parsed arguments
   * @throws LDAPConnectionException
   *           if there was a problem connecting to the server indicated by the
   *           input arguments
   * @throws ArgumentException
   *           if there was a problem processing the input arguments
   */
  private LDAPConnection connect(SecureConnectionCliArgs args, PrintStream out, PrintStream err)
      throws LDAPConnectionException, ArgumentException
  {
    throwIfArgumentsConflict(args.getBindPasswordArg(), args.getBindPasswordFileArg());
    throwIfArgumentsConflict(args.getKeyStorePasswordArg(), args.getKeyStorePasswordFileArg());
    throwIfArgumentsConflict(args.getTrustStorePasswordArg(), args.getTrustStorePasswordFileArg());
    throwIfArgumentsConflict(args.getUseSSLArg(), args.getUseStartTLSArg());

    // Create the LDAP connection options object, which will be used to
    // customize the way that we connect to the server and specify a set of
    // basic defaults.
    LDAPConnectionOptions connectionOptions = new LDAPConnectionOptions();
    connectionOptions.setVersionNumber(3);

    // See if we should use SSL or StartTLS when establishing the connection.
    // If so, then make sure only one of them was specified.
    if (args.getUseSSLArg().isPresent())
    {
      connectionOptions.setUseSSL(true);
    }
    else if (args.getUseStartTLSArg().isPresent())
    {
      connectionOptions.setStartTLS(true);
    }

    // If we should blindly trust any certificate, then install the appropriate
    // SSL connection factory.
    if (args.getUseSSLArg().isPresent() || args.getUseStartTLSArg().isPresent())
    {
      try
      {
        String clientAlias;
        if (args.getCertNicknameArg().isPresent())
        {
          clientAlias = args.getCertNicknameArg().getValue();
        }
        else
        {
          clientAlias = null;
        }

        SSLConnectionFactory sslConnectionFactory = new SSLConnectionFactory();
        sslConnectionFactory.init(args.getTrustAllArg().isPresent(),
                                  args.getKeyStorePathArg().getValue(),
                                  getFirstArgumentValue(args.getKeyStorePasswordArg(), args.getKeyStorePasswordFileArg()),
                                  clientAlias,
                                  args.getTrustStorePathArg().getValue(),
                                  getFirstArgumentValue(args.getTrustStorePasswordArg(), args.getTrustStorePasswordFileArg()));
        connectionOptions.setSSLConnectionFactory(sslConnectionFactory);
      }
      catch (SSLConnectionException sce)
      {
        printWrappedText(err, ERR_LDAP_CONN_CANNOT_INITIALIZE_SSL.get(sce.getMessage()));
      }
    }

    // If one or more SASL options were provided, then make sure that one of
    // them was "mech" and specified a valid SASL mechanism.
    if (args.getSaslOptionArg().isPresent())
    {
      String mechanism = null;
      LinkedList<String> options = new LinkedList<>();

      for (String s : args.getSaslOptionArg().getValues())
      {
        int equalPos = s.indexOf('=');
        if (equalPos <= 0)
        {
          printAndThrowException(err, ERR_LDAP_CONN_CANNOT_PARSE_SASL_OPTION.get(s));
        }
        else
        {
          String name = s.substring(0, equalPos);
          if ("mech".equalsIgnoreCase(name))
          {
            mechanism = s;
          }
          else
          {
            options.add(s);
          }
        }
      }

      if (mechanism == null)
      {
        printAndThrowException(err, ERR_LDAP_CONN_NO_SASL_MECHANISM.get());
      }

      connectionOptions.setSASLMechanism(mechanism);
      for (String option : options)
      {
        connectionOptions.addSASLProperty(option);
      }
    }

    int timeout = args.getConnectTimeoutArg().getIntValue();

    final String passwordValue = getPasswordValue(
            args.getBindPasswordArg(), args.getBindPasswordFileArg(), args.getBindDnArg(), out, err);
    return connect(
            args.getHostNameArg().getValue(),
            args.getPortArg().getIntValue(),
            args.getBindDnArg().getValue(),
            passwordValue,
            connectionOptions, timeout, out, err);
  }

  private void printAndThrowException(PrintStream err, LocalizableMessage message) throws ArgumentException
  {
    printWrappedText(err, message);
    throw new ArgumentException(message);
  }

  /**
   * Creates a connection using a console interaction that will be used to
   * potentially interact with the user to prompt for necessary information for
   * establishing the connection.
   *
   * @param ui
   *          user interaction for prompting the user
   * @param out
   *          stream to write messages
   * @param err
   *          stream to write error messages
   * @return LDAPConnection created by this class from parsed arguments
   * @throws SSLConnectionException
   *           if there was a problem connecting with SSL to the server
   * @throws LDAPConnectionException
   *           if there was any other problem connecting to the server
   * @throws ArgumentException
   *           if there was a problem indicated by the input arguments
   */
  public LDAPConnection connect(LDAPConnectionConsoleInteraction ui, PrintStream out, PrintStream err)
      throws LDAPConnectionException, SSLConnectionException, ArgumentException
  {
    try
    {
      ui.run();
      LDAPConnectionOptions options = new LDAPConnectionOptions();
      options.setVersionNumber(3);
      return connect(ui.getHostName(), ui.getPortNumber(), ui.getBindDN().toString(),
          ui.getBindPassword(), ui.populateLDAPOptions(options), ui.getConnectTimeout(), out, err);
    }
    catch (OpenDsException e)
    {
      err.println(isSSLException(e) ?
          ERR_TASKINFO_LDAP_EXCEPTION_SSL.get(ui.getHostName(), ui.getPortNumber()) : e.getMessageObject());
      throw e;
    }
  }

  private boolean isSSLException(Exception e)
  {
    return e.getCause() != null
        && e.getCause().getCause() != null
        && e.getCause().getCause() instanceof SSLException;
  }

  /**
   * Creates a connection from information provided.
   *
   * @param host
   *          of the server
   * @param port
   *          of the server
   * @param bindDN
   *          with which to connect
   * @param bindPw
   *          with which to connect
   * @param options
   *          with which to connect
   * @param timeout
   *          the timeout to establish the connection in milliseconds. Use
   *          {@code 0} to express no timeout
   * @param out
   *          stream to write messages
   * @param err
   *          stream to write error messages
   * @return LDAPConnection created by this class from parsed arguments
   * @throws LDAPConnectionException
   *           if there was a problem connecting to the server indicated by the
   *           input arguments
   */
  private LDAPConnection connect(String host, int port, String bindDN, String bindPw, LDAPConnectionOptions options,
      int timeout, PrintStream out, PrintStream err) throws LDAPConnectionException
  {
    // Attempt to connect and authenticate to the Directory Server.
    AtomicInteger nextMessageID = new AtomicInteger(1);
    LDAPConnection connection = new LDAPConnection(host, port, options, out, err);
    connection.connectToHost(bindDN, bindPw, nextMessageID, timeout);
    return connection;
  }

  /**
   * Gets the arguments associated with this parser.
   *
   * @return arguments for this parser.
   */
  public SecureConnectionCliArgs getArguments()
  {
    return args;
  }

  /**
   * Commodity method that retrieves the password value analyzing the contents
   * of a string argument and of a file based argument. It assumes that the
   * arguments have already been parsed and validated. If the string is a dash,
   * or no password is available, it will prompt for it on the command line.
   *
   * @param bindPwdArg
   *          the string argument for the password.
   * @param bindPwdFileArg
   *          the file based argument for the password.
   * @param bindDnArg
   *          the string argument for the bindDN.
   * @param out
   *          stream to write message.
   * @param err
   *          stream to write error message.
   * @return the password value.
   */
  public static String getPasswordValue(StringArgument bindPwdArg, FileBasedArgument bindPwdFileArg,
      StringArgument bindDnArg, PrintStream out, PrintStream err)
  {
    try
    {
      return getPasswordValue(bindPwdArg, bindPwdFileArg, bindDnArg.getValue(), out, err);
    }
    catch (Exception ex)
    {
      printWrappedText(err, ex.getMessage());
      return null;
    }
  }

  /**
   * Commodity method that retrieves the password value analyzing the contents
   * of a string argument and of a file based argument. It assumes that the
   * arguments have already been parsed and validated. If the string is a dash,
   * or no password is available, it will prompt for it on the command line.
   *
   * @param bindPassword
   *          the string argument for the password.
   * @param bindPasswordFile
   *          the file based argument for the password.
   * @param bindDNValue
   *          the string value for the bindDN.
   * @param out
   *          stream to write message.
   * @param err
   *          stream to write error message.
   * @return the password value.
   * @throws ClientException
   *           if the password cannot be read
   */
  public static String getPasswordValue(StringArgument bindPassword, FileBasedArgument bindPasswordFile,
      String bindDNValue, PrintStream out, PrintStream err) throws ClientException
  {
    String bindPasswordValue = bindPassword.getValue();
    if ("-".equals(bindPasswordValue)
        || (!bindPasswordFile.isPresent() && bindDNValue != null && bindPasswordValue == null))
    {
      // read the password from the stdin.
      out.print(INFO_LDAPAUTH_PASSWORD_PROMPT.get(bindDNValue));
      char[] pwChars = ConsoleApplication.readPassword();
      // As per rfc 4513(section-5.1.2) a client should avoid sending
      // an empty password to the server.
      while (pwChars.length == 0)
      {
        printWrappedText(err, INFO_LDAPAUTH_NON_EMPTY_PASSWORD.get());
        out.print(INFO_LDAPAUTH_PASSWORD_PROMPT.get(bindDNValue));
        pwChars = ConsoleApplication.readPassword();
      }
      return new String(pwChars);
    }
    else if (bindPasswordValue == null)
    {
      // Read from file if it exists.
      return bindPasswordFile.getValue();
    }
    return bindPasswordValue;
  }

  private void addLdapConnectionArguments(ArgumentGroup argGroup, boolean alwaysSSL)
  {
    args = new SecureConnectionCliArgs(alwaysSSL);
    try
    {
      Set<Argument> argSet = args.createGlobalArguments();
      for (Argument arg : argSet)
      {
        addArgument(arg, argGroup);
      }
    }
    catch (ArgumentException ae)
    {
      ae.printStackTrace(); // Should never happen
    }
  }
}
