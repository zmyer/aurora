/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.http.api.security;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedAction;
import java.util.Optional;

import javax.inject.Singleton;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import com.google.inject.AbstractModule;
import com.google.inject.PrivateModule;
import com.sun.security.auth.login.ConfigFile;
import com.sun.security.auth.module.Krb5LoginModule;

import org.apache.aurora.scheduler.config.CliOptions;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures and provides a Shiro {@link org.apache.shiro.realm.Realm}.
 *
 * @see org.apache.aurora.scheduler.http.api.security.Kerberos5Realm
 */
public class Kerberos5ShiroRealmModule extends AbstractModule {
  private static final Logger LOG = LoggerFactory.getLogger(Kerberos5ShiroRealmModule.class);

  /**
   * Standard Object Identifier for the Kerberos 5 GSS-API mechanism.
   */
  private static final String GSS_KRB5_MECH_OID = "1.2.840.113554.1.2.2";

  /**
   * Standard Object Identifier for the SPNEGO GSS-API mechanism.
   */
  private static final String GSS_SPNEGO_MECH_OID = "1.3.6.1.5.5.2";

  private static final String JAAS_CONF_TEMPLATE =
      "%s {\n"
          + Krb5LoginModule.class.getName()
          + " required useKeyTab=true storeKey=true doNotPrompt=true isInitiator=false "
          + "keyTab=\"%s\" principal=\"%s\" debug=%s;\n"
          + "};";

  @Parameters(separators = "=")
  public static class Options {
    private static final String SERVER_KEYTAB_ARGNAME = "-kerberos_server_keytab";
    private static final String SERVER_PRINCIPAL_ARGNAME = "-kerberos_server_principal";

    @Parameter(names = SERVER_KEYTAB_ARGNAME, description = "Path to the server keytab.")
    public File serverKeytab;

    @Parameter(names = SERVER_PRINCIPAL_ARGNAME,
        description = "Kerberos server principal to use, usually of the form "
            + "HTTP/aurora.example.com@EXAMPLE.COM")
    public KerberosPrincipal serverPrincipal;

    @Parameter(names = "-kerberos_debug",
        description = "Produce additional Kerberos debugging output.",
        arity = 1)
    public boolean kerberosDebug = false;
  }

  private final Optional<File> serverKeyTab;
  private final Optional<KerberosPrincipal> serverPrincipal;
  private final GSSManager gssManager;
  private final boolean kerberosDebugEnabled;

  public Kerberos5ShiroRealmModule(CliOptions options) {
    this(
        Optional.ofNullable(options.kerberos.serverKeytab),
        Optional.ofNullable(options.kerberos.serverPrincipal),
        GSSManager.getInstance(),
        options.kerberos.kerberosDebug);
  }

  @VisibleForTesting
  Kerberos5ShiroRealmModule(
      File serverKeyTab,
      KerberosPrincipal serverPrincipal,
      GSSManager gssManager) {

    this(
        Optional.of(serverKeyTab),
        Optional.of(serverPrincipal),
        gssManager,
        true);
  }

  private Kerberos5ShiroRealmModule(
      Optional<File> serverKeyTab,
      Optional<KerberosPrincipal> serverPrincipal,
      GSSManager gssManager,
      boolean kerberosDebugEnabled) {

    this.serverKeyTab = serverKeyTab;
    this.serverPrincipal = serverPrincipal;
    this.gssManager = gssManager;
    this.kerberosDebugEnabled = kerberosDebugEnabled;
  }

  @Override
  protected void configure() {
    if (!serverKeyTab.isPresent()) {
      addError("No -" + Options.SERVER_KEYTAB_ARGNAME + " specified.");
      return;
    }

    if (!serverPrincipal.isPresent()) {
      addError("No -" + Options.SERVER_PRINCIPAL_ARGNAME + " specified.");
      return;
    }

    // TODO(ksweeney): Find a better way to configure JAAS in code.
    String jaasConf = String.format(
        JAAS_CONF_TEMPLATE,
        getClass().getName(),
        serverKeyTab.get().getAbsolutePath(),
        serverPrincipal.get().getName(),
        kerberosDebugEnabled);
    LOG.debug("Generated jaas.conf: " + jaasConf);

    File jaasConfFile;
    try {
      jaasConfFile = File.createTempFile("jaas", "conf");
      jaasConfFile.deleteOnExit();
      Files.asCharSink(jaasConfFile, StandardCharsets.UTF_8).write(jaasConf);
    } catch (IOException e) {
      addError(e);
      return;
    }

    GSSCredential serverCredential;
    try {
      LoginContext loginContext = new LoginContext(
          getClass().getName(),
          null /* subject (read from jaas config file passed below) */,
          null /* callbackHandler */,
          new ConfigFile(jaasConfFile.toURI()));
      loginContext.login();
      serverCredential = Subject.doAs(
          loginContext.getSubject(),
          (PrivilegedAction<GSSCredential>) () -> {
            try {
              return gssManager.createCredential(
                  null /* Use the service principal name defined in jaas.conf */,
                  GSSCredential.INDEFINITE_LIFETIME,
                  new Oid[] {new Oid(GSS_SPNEGO_MECH_OID), new Oid(GSS_KRB5_MECH_OID)},
                  GSSCredential.ACCEPT_ONLY);
            } catch (GSSException e) {
              throw new RuntimeException(e);
            }
          });
    } catch (LoginException e) {
      addError(e);
      return;
    }

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(GSSManager.class).toInstance(gssManager);
        bind(GSSCredential.class).toInstance(serverCredential);

        bind(Kerberos5Realm.class).in(Singleton.class);
        expose(Kerberos5Realm.class);
      }
    });
    ShiroUtils.addRealmBinding(binder()).to(Kerberos5Realm.class);
  }
}
