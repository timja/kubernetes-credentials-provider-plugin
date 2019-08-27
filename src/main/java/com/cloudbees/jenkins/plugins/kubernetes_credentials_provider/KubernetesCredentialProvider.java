/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.kubernetes_credentials_provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.AdministrativeMonitor;
import hudson.util.AdministrativeError;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import org.acegisecurity.Authentication;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.TermMilestone;
import hudson.init.Terminator;
import hudson.model.ItemGroup;
import hudson.model.ModelObject;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.common.IdCredentials;

@Extension
public class KubernetesCredentialProvider extends CredentialsProvider implements Watcher<Secret> {

    private static final Logger LOG = Logger.getLogger(KubernetesCredentialProvider.class.getName());

    /** Map of Credentials keyed by their credential ID */
    private ConcurrentHashMap<String, IdCredentials> credentials = new ConcurrentHashMap<>();

    @CheckForNull
    private KubernetesClient client;
    @CheckForNull
    private Watch watch;

    private KubernetesCredentialsStore store = new KubernetesCredentialsStore(this);

    KubernetesClient getKubernetesClient() {
        if (client == null) {
            ConfigBuilder cb = new ConfigBuilder();
            Config config = cb.build();
            client = new DefaultKubernetesClient(config);
        }
        return client;
    }

    @Initializer(after=InitMilestone.PLUGINS_PREPARED, fatal=false)
    @Restricted(NoExternalUse.class) // only for callbacks from Jenkins
    public void startWatchingForSecrets() {
        final String initAdminMonitorId = getClass().getName() + ".initialize";
        try {
            KubernetesClient _client = getKubernetesClient();
            LOG.log(Level.FINER, "Using namespace: {0}", String.valueOf(_client.getNamespace()));

            // start watching new secrets before we list the current set of secrets so we don't miss any events
            LOG.log(Level.FINER, "registering watch");
            watch = _client.secrets().withLabel(SecretUtils.JENKINS_IO_CREDENTIALS_TYPE_LABEL).watch(this);
            LOG.log(Level.FINER, "registered watch, retrieving secrets");

            // load current set of secrets into provider
            LOG.log(Level.FINER, "retrieving secrets");
            SecretList list = _client.secrets().withLabel(SecretUtils.JENKINS_IO_CREDENTIALS_TYPE_LABEL).list();
            // reset credentials to the latest full listing
            credentials.clear();
            List<Secret> secretList = list.getItems();
            for (Secret s : secretList) {
                LOG.log(Level.FINE, "Secret Added - {0}", SecretUtils.getCredentialId(s));
                addSecret(s);
            }

            // successfully initialized, clear any previous monitors
            clearAdminMonitors(initAdminMonitorId);
        } catch (KubernetesClientException kex) {
            LOG.log(Level.SEVERE, "Failed to initialise k8s secret provider, secrets from Kubernetes will not be available", kex);
            new AdministrativeError(initAdminMonitorId,
                    "Failed to initialize Kubernetes secret provider",
                    "Credentials from Kubernetes Secrets will not be available.", kex);
        }
    }

    private void clearAdminMonitors(String id) {
        ExtensionList<AdministrativeMonitor> all = AdministrativeMonitor.all();
        List<AdministrativeMonitor> toRemove = all.stream().filter(am -> StringUtils.equals(id, am.id)).collect(Collectors.toList());
        all.removeAll(toRemove);
    }

    @Terminator(after=TermMilestone.STARTED)
    @Restricted(NoExternalUse.class) // only for callbacks from Jenkins
    public void stopWatchingForSecrets() {
        if (watch != null) {
            watch.close();
            watch = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public <C extends Credentials> List<C> getCredentials(Class<C> type, ItemGroup itemGroup, Authentication authentication) {
        LOG.log(Level.FINEST, "getCredentials called with type {0} and authentication {1}", new Object[] {type.getName(), authentication});
        if (ACL.SYSTEM.equals(authentication)) {
            ArrayList<C> list = new ArrayList<>();
            for (IdCredentials credential : credentials.values()) {
                // is s a type of type then populate the list...
                LOG.log(Level.FINEST, "getCredentials {0} is a possible candidate", credential.getId());
                if (type.isAssignableFrom(credential.getClass())) {
                    LOG.log(Level.FINEST, "getCredentials {0} matches, adding to list", credential.getId());
                    // cast to keep generics happy even though we are assignable..
                    list.add(type.cast(credential));
                } else {
                    LOG.log(Level.FINEST, "getCredentials {0} does not match", credential.getId());
                }
            }
            return list;
        }
        return emptyList();
    }

    @SuppressWarnings("null")
    private final @NonNull <T> List<T> emptyList() {
        // just a separate method to avoid having to suppress "null" for the entirety of getCredentials
        return Collections.emptyList();
    }

    private void addSecret(Secret secret) {
        IdCredentials cred = convertSecret(secret);
        if (cred != null) {
            credentials.put(SecretUtils.getCredentialId(secret), cred);
        }
    }

    @Override
    public void eventReceived(Action action, Secret secret) {
        String credentialId = SecretUtils.getCredentialId(secret);
        switch (action) {
            case ADDED: {
                LOG.log(Level.FINE, "Secret Added - {0}", credentialId);
                addSecret(secret);
                break;
            }
            case MODIFIED: {
                LOG.log(Level.FINE, "Secret Modified - {0}", credentialId);
                addSecret(secret);
                break;
            }
            case DELETED: {
                LOG.log(Level.FINE, "Secret Deleted - {0}", credentialId);
                credentials.remove(credentialId);
                break;
            }
            case ERROR:
                // XXX  ????
                LOG.log(Level.WARNING, "Action received of type Error. {0}", secret);
        }
    }

    @Override
    public void onClose(KubernetesClientException cause) {
        if (cause != null) {
            LOG.log(Level.WARNING, "Secrets watch stopped unexpectedly", cause);
            LOG.log(Level.INFO, "Restating secrets watcher");
            startWatchingForSecrets();
        } else {
            LOG.log(Level.INFO, "Secrets watcher stopped");
        }
    }


    @CheckForNull
    IdCredentials convertSecret(Secret s) {
        String type = s.getMetadata().getLabels().get(SecretUtils.JENKINS_IO_CREDENTIALS_TYPE_LABEL);

        SecretToCredentialConverter lookup = SecretToCredentialConverter.lookup(type);
        if (lookup != null) {
            try {
                return lookup.convert(s);
            } catch (CredentialsConvertionException ex) {
                // do not spam the logs with the stacktrace...
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Failed to convert Secret '" + SecretUtils.getCredentialId(s) + "' of type " + type, ex);
                }
                else {
                    LOG.log(Level.WARNING, "Failed to convert Secret ''{0}'' of type {1} due to {2}", new Object[] {SecretUtils.getCredentialId(s), type, ex.getMessage()});
                }
                return null;
            }
        }
        LOG.log(Level.WARNING, "No SecretToCredentialConverter found to convert secrets of type {0}", type);
        return null;
    }

    @Override
    public CredentialsStore getStore(ModelObject object) {
        return object == Jenkins.getInstance() ? store : null;
    }

    @Override
    public String getIconClassName() {
        return "icon-credentials-kubernetes-store";
    }

}
