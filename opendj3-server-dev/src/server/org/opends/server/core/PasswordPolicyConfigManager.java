/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2010 Sun Microsystems, Inc.
 *      Portions Copyright 2011-2014 ForgeRock AS.
 */
package org.opends.server.core;



import static org.opends.messages.ConfigMessages.*;
import static org.opends.server.util.StaticUtils.stackTraceToSingleLineString;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.forgerock.i18n.LocalizableMessage;
import org.opends.server.admin.ClassPropertyDefinition;
import org.opends.server.admin.server.ConfigurationAddListener;
import org.opends.server.admin.server.ConfigurationDeleteListener;
import org.opends.server.admin.server.ServerManagementContext;
import org.opends.server.admin.std.meta.AuthenticationPolicyCfgDefn;
import org.opends.server.admin.std.server.AuthenticationPolicyCfg;
import org.opends.server.admin.std.server.PasswordPolicyCfg;
import org.opends.server.admin.std.server.RootCfg;
import org.opends.server.api.AuthenticationPolicy;
import org.opends.server.api.AuthenticationPolicyFactory;
import org.opends.server.api.SubentryChangeListener;
import org.opends.server.config.ConfigException;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.opends.server.types.*;
import org.forgerock.opendj.ldap.ResultCode;



/**
 * This class defines a utility that will be used to manage the set of password
 * policies defined in the Directory Server. It will initialize the policies
 * when the server starts, and then will manage any additions or removals while
 * the server is running.
 */
final class PasswordPolicyConfigManager implements SubentryChangeListener,
    ConfigurationAddListener<AuthenticationPolicyCfg>,
    ConfigurationDeleteListener<AuthenticationPolicyCfg>
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  private final ServerContext serverContext;

  /**
   * Creates a new instance of this password policy config manager.
   *
   * @param serverContext
   *            The server context.
   */
  public PasswordPolicyConfigManager(ServerContext serverContext)
  {
    this.serverContext = serverContext;
  }



  /**
   * Initializes all authentication policies currently defined in the Directory
   * Server configuration. This should only be called at Directory Server
   * startup.
   *
   * @throws ConfigException
   *           If a configuration problem causes the authentication policy
   *           initialization process to fail.
   * @throws InitializationException
   *           If a problem occurs while initializing the authentication
   *           policies that is not related to the server configuration.
   */
  public void initializeAuthenticationPolicies() throws ConfigException,
      InitializationException
  {
    // Get the root configuration object.
    ServerManagementContext managementContext = ServerManagementContext
        .getInstance();
    RootCfg rootConfiguration = managementContext.getRootConfiguration();

    // Register as an add and delete listener with the root configuration so we
    // can be notified if any password policy entries are added or removed.
    rootConfiguration.addPasswordPolicyAddListener(this);
    rootConfiguration.addPasswordPolicyDeleteListener(this);

    // First, get the configuration base entry.
    String[] passwordPolicyNames = rootConfiguration.listPasswordPolicies();

    // See if the base entry has any children. If not, then that means that
    // there are no policies defined, so that's a problem.
    if (passwordPolicyNames.length == 0)
    {
      LocalizableMessage message = ERR_CONFIG_PWPOLICY_NO_POLICIES.get();
      throw new ConfigException(message);
    }

    // Get the DN of the default password policy from the core configuration.
    if (DirectoryServer.getDefaultPasswordPolicyDN() == null)
    {
      LocalizableMessage message = ERR_CONFIG_PWPOLICY_NO_DEFAULT_POLICY.get();
      throw new ConfigException(message);
    }

    // Iterate through the child entries and process them as password policy
    // configuration entries.
    for (String passwordPolicyName : passwordPolicyNames)
    {
      AuthenticationPolicyCfg passwordPolicyConfiguration = rootConfiguration
          .getPasswordPolicy(passwordPolicyName);
      createAuthenticationPolicy(passwordPolicyConfiguration);
    }

    // If the entry specified by the default password policy DN has not been
    // registered, then fail.
    if (null == DirectoryServer.getDefaultPasswordPolicy())
    {
      DN defaultPolicyDN = DirectoryServer.getDefaultPasswordPolicyDN();
      throw new ConfigException(ERR_CONFIG_PWPOLICY_MISSING_DEFAULT_POLICY.get(defaultPolicyDN));
    }

    // Process and register any password policy subentries.
    List<SubEntry> pwpSubEntries = DirectoryServer.getSubentryManager().getSubentries();
    if ((pwpSubEntries != null) && (!pwpSubEntries.isEmpty()))
    {
      for (SubEntry subentry : pwpSubEntries)
      {
        if (subentry.getEntry().isPasswordPolicySubentry())
        {
          try
          {
            PasswordPolicy policy = new SubentryPasswordPolicy(subentry);
            DirectoryServer.registerAuthenticationPolicy(subentry.getDN(),
                policy);
          }
          catch (Exception e)
          {
            // Just log a message instead of failing the server initialization.
            // This will allow the administrator to fix any problems.
            logger.error(ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG, subentry.getDN(), stackTraceToSingleLineString(e));
          }
        }
      }
    }

    // Register this as subentry change listener with SubentryManager.
    DirectoryServer.getSubentryManager().registerChangeListener(this);
  }



  /**
   * Perform any required finalization tasks for all authentication policies
   * currently defined. This should only be called at Directory Server shutdown.
   */
  public void finalizeAuthenticationPolicies()
  {
    // Deregister this as subentry change listener with SubentryManager.
    DirectoryServer.getSubentryManager().deregisterChangeListener(this);

    // Deregister as configuration change listeners.
    ServerManagementContext managementContext = ServerManagementContext
        .getInstance();
    RootCfg rootConfiguration = managementContext.getRootConfiguration();
    rootConfiguration.removePasswordPolicyAddListener(this);
    rootConfiguration.removePasswordPolicyDeleteListener(this);
  }



  /**
   * {@inheritDoc}
   */
  public boolean isConfigurationAddAcceptable(
      AuthenticationPolicyCfg configuration, List<LocalizableMessage> unacceptableReason)
  {
    // See if we can create a password policy from the provided configuration
    // entry. If so, then it's acceptable.
    return isAuthenticationPolicyConfigurationAcceptable(configuration,
        unacceptableReason);
  }



  /**
   * {@inheritDoc}
   */
  public ConfigChangeResult applyConfigurationAdd(
      AuthenticationPolicyCfg configuration)
  {
    // See if we can create a password policy from the provided configuration
    // entry. If so, then register it with the Directory Server.
    ArrayList<LocalizableMessage> messages = new ArrayList<LocalizableMessage>();
    try
    {
      createAuthenticationPolicy(configuration);
      return new ConfigChangeResult(ResultCode.SUCCESS, false, messages);
    }
    catch (ConfigException ce)
    {
      messages.add(ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG.get(configuration.dn(), ce.getMessage()));
      return new ConfigChangeResult(ResultCode.CONSTRAINT_VIOLATION, false, messages);
    }
    catch (InitializationException ie)
    {
      messages.add(ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG.get(configuration.dn(), ie.getMessage()));
      return new ConfigChangeResult(DirectoryServer.getServerErrorResultCode(), false, messages);
    }
    catch (Exception e)
    {
      messages.add(ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG.get(configuration.dn(), stackTraceToSingleLineString(e)));
      return new ConfigChangeResult(DirectoryServer.getServerErrorResultCode(), false, messages);
    }
  }



  /**
   * {@inheritDoc}
   */
  public boolean isConfigurationDeleteAcceptable(
      AuthenticationPolicyCfg configuration, List<LocalizableMessage> unacceptableReason)
  {
    // We'll allow the policy to be removed as long as it isn't the default.
    // FIXME: something like a referential integrity check is needed to ensure
    // a policy is not removed when referenced by a user entry (either
    // directly or via a virtual attribute).
    DN defaultPolicyDN = DirectoryServer.getDefaultPasswordPolicyDN();
    if ((defaultPolicyDN != null) && defaultPolicyDN.equals(configuration.dn()))
    {
      unacceptableReason.add(WARN_CONFIG_PWPOLICY_CANNOT_DELETE_DEFAULT_POLICY.get(defaultPolicyDN));
      return false;
    }
    else
    {
      return true;
    }
  }



  /**
   * {@inheritDoc}
   */
  public ConfigChangeResult applyConfigurationDelete(
      AuthenticationPolicyCfg configuration)
  {
    // We'll allow the policy to be removed as long as it isn't the default.
    // FIXME: something like a referential integrity check is needed to ensure
    // a policy is not removed when referenced by a user entry (either
    // directly or via a virtual attribute).
    ArrayList<LocalizableMessage> messages = new ArrayList<LocalizableMessage>(1);
    DN policyDN = configuration.dn();
    DN defaultPolicyDN = DirectoryServer.getDefaultPasswordPolicyDN();
    if ((defaultPolicyDN != null) && defaultPolicyDN.equals(policyDN))
    {
      messages.add(WARN_CONFIG_PWPOLICY_CANNOT_DELETE_DEFAULT_POLICY.get(defaultPolicyDN));
      return new ConfigChangeResult(ResultCode.CONSTRAINT_VIOLATION, false, messages);
    }
    DirectoryServer.deregisterAuthenticationPolicy(policyDN);
    messages.add(INFO_CONFIG_PWPOLICY_REMOVED_POLICY.get(policyDN));

    return new ConfigChangeResult(ResultCode.SUCCESS, false, messages);
  }



  /**
   * {@inheritDoc}
   */
  public void checkSubentryAddAcceptable(Entry entry) throws DirectoryException
  {
    if (entry.isPasswordPolicySubentry())
    {
      new SubentryPasswordPolicy(new SubEntry(entry));
    }
  }



  /**
   * {@inheritDoc}
   */
  public void checkSubentryDeleteAcceptable(Entry entry)
      throws DirectoryException
  {
    // FIXME: something like a referential integrity check is needed to
    // ensure a policy is not removed when referenced by a user entry (
    // either directly or via a virtual attribute).
  }



  /**
   * {@inheritDoc}
   */
  public void checkSubentryModifyAcceptable(Entry oldEntry, Entry newEntry)
      throws DirectoryException
  {
    if (newEntry.isPasswordPolicySubentry())
    {
      new SubentryPasswordPolicy(new SubEntry(newEntry));
    }
  }



  /**
   * {@inheritDoc}
   */
  public void checkSubentryModifyDNAcceptable(Entry oldEntry, Entry newEntry)
      throws DirectoryException
  {
    // FIXME: something like a referential integrity check is needed to
    // ensure a policy is not removed when referenced by a user entry (
    // either directly or via a virtual attribute).
  }



  /**
   * {@inheritDoc}
   */
  public void handleSubentryAdd(Entry entry)
  {
    if (entry.isPasswordPolicySubentry())
    {
      try
      {
        PasswordPolicy policy = new SubentryPasswordPolicy(new SubEntry(entry));
        DirectoryServer.registerAuthenticationPolicy(entry.getName(), policy);
      }
      catch (Exception e)
      {
        if (logger.isTraceEnabled())
        {
          logger.trace("Could not create password policy subentry DN %s: %s",
              entry.getName(), stackTraceToSingleLineString(e));
        }
      }
    }
  }



  /**
   * {@inheritDoc}
   */
  public void handleSubentryDelete(Entry entry)
  {
    if (entry.isPasswordPolicySubentry())
    {
      DirectoryServer.deregisterAuthenticationPolicy(entry.getName());
    }
  }



  /**
   * {@inheritDoc}
   */
  public void handleSubentryModify(Entry oldEntry, Entry newEntry)
  {
    if (oldEntry.isPasswordPolicySubentry())
    {
      DirectoryServer.deregisterAuthenticationPolicy(oldEntry.getName());
    }

    if (newEntry.isPasswordPolicySubentry())
    {
      try
      {
        PasswordPolicy policy = new SubentryPasswordPolicy(new SubEntry(
            newEntry));
        DirectoryServer.registerAuthenticationPolicy(newEntry.getName(),
            policy);
      }
      catch (Exception e)
      {
        if (logger.isTraceEnabled())
        {
          logger.trace("Could not create password policy subentry DN %s: %s",
              newEntry.getName(), stackTraceToSingleLineString(e));
        }
      }
    }
  }



  /**
   * {@inheritDoc}
   */
  public void handleSubentryModifyDN(Entry oldEntry, Entry newEntry)
  {
    if (oldEntry.isPasswordPolicySubentry())
    {
      DirectoryServer.deregisterAuthenticationPolicy(oldEntry.getName());
    }

    if (newEntry.isPasswordPolicySubentry())
    {
      try
      {
        PasswordPolicy policy = new SubentryPasswordPolicy(new SubEntry(
            newEntry));
        DirectoryServer.registerAuthenticationPolicy(newEntry.getName(),
            policy);
      }
      catch (Exception e)
      {
        if (logger.isTraceEnabled())
        {
          logger.trace("Could not create password policy subentry DN %s: %s",
              newEntry.getName(), stackTraceToSingleLineString(e));
        }
      }
    }
  }



  // Creates and registers the provided authentication policy
  // configuration.
  private void createAuthenticationPolicy(
      AuthenticationPolicyCfg policyConfiguration) throws ConfigException,
      InitializationException
  {
    // If this is going to be the default password policy then check the type is
    // correct.
    if (policyConfiguration.dn().equals(
        DirectoryServer.getDefaultPasswordPolicyDN()))
    {
      if (!(policyConfiguration instanceof PasswordPolicyCfg))
      {
        LocalizableMessage msg = ERR_CONFIG_PWPOLICY_DEFAULT_POLICY_IS_WRONG_TYPE.get(policyConfiguration.dn());
        throw new ConfigException(msg);
      }
    }

    String className = policyConfiguration.getJavaClass();
    AuthenticationPolicyCfgDefn d = AuthenticationPolicyCfgDefn.getInstance();
    ClassPropertyDefinition pd = d.getJavaClassPropertyDefinition();

    // Load the class and cast it to an authentication policy.
    Class<?> theClass;
    AuthenticationPolicyFactory<?> factory;

    try
    {
      theClass = pd.loadClass(className, AuthenticationPolicyFactory.class);
      factory = (AuthenticationPolicyFactory<?>) theClass.newInstance();
      factory.setServerContext(serverContext);
    }
    catch (Exception e)
    {
      logger.traceException(e);

      LocalizableMessage message = ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG.get(
          policyConfiguration.dn(), stackTraceToSingleLineString(e));
      throw new InitializationException(message, e);
    }

    // Perform the necessary initialization for the authentication policy.
    AuthenticationPolicy policy;
    try
    {
      // Determine the initialization method to use: it must take a
      // single parameter which is the exact type of the configuration
      // object.
      Method method = theClass.getMethod("createAuthenticationPolicy",
          policyConfiguration.configurationClass());

      policy = (AuthenticationPolicy) method.invoke(factory,
          policyConfiguration);
    }
    catch (Exception e)
    {
      if (e instanceof InvocationTargetException)
      {
        Throwable t = e.getCause();

        if (t instanceof InitializationException)
        {
          throw (InitializationException) t;
        }
        else if (t instanceof ConfigException)
        {
          throw (ConfigException) t;
        }
      }

      logger.traceException(e);

      LocalizableMessage message = ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG.get(
          policyConfiguration.dn(), stackTraceToSingleLineString(e));
      throw new InitializationException(message, e);
    }

    DirectoryServer.registerAuthenticationPolicy(policyConfiguration.dn(), policy);
  }



  // Determines whether or not the new authentication policy configuration's
  // implementation class is acceptable.
  private boolean isAuthenticationPolicyConfigurationAcceptable(
      AuthenticationPolicyCfg policyConfiguration,
      List<LocalizableMessage> unacceptableReasons)
  {
    // If this is going to be the default password policy then check the type is
    // correct.
    if (policyConfiguration.dn().equals(
        DirectoryServer.getDefaultPasswordPolicyDN()))
    {
      if (!(policyConfiguration instanceof PasswordPolicyCfg))
      {
        LocalizableMessage msg = ERR_CONFIG_PWPOLICY_DEFAULT_POLICY_IS_WRONG_TYPE.get(policyConfiguration.dn());
        unacceptableReasons.add(msg);
        return false;
      }
    }

    String className = policyConfiguration.getJavaClass();
    AuthenticationPolicyCfgDefn d = AuthenticationPolicyCfgDefn.getInstance();
    ClassPropertyDefinition pd = d.getJavaClassPropertyDefinition();

    // Validate the configuration.
    try
    {
      // Load the class and cast it to a authentication policy factory.
      Class<?> theClass;
      AuthenticationPolicyFactory<?> factory;

      theClass = pd.loadClass(className, AuthenticationPolicyFactory.class);
      factory = (AuthenticationPolicyFactory<?>) theClass.newInstance();
      factory.setServerContext(serverContext);

      // Determine the initialization method to use: it must take a
      // single parameter which is the exact type of the configuration
      // object.
      Method method = theClass.getMethod("isConfigurationAcceptable",
          AuthenticationPolicyCfg.class, List.class);
      return (Boolean) method.invoke(factory, policyConfiguration,
          unacceptableReasons);
    }
    catch (Exception e)
    {
      logger.traceException(e);

      unacceptableReasons.add(ERR_CONFIG_PWPOLICY_INVALID_POLICY_CONFIG.get(
          policyConfiguration.dn(), stackTraceToSingleLineString(e)));
      return false;
    }
  }

}
