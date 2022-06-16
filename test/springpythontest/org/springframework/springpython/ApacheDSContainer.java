package org.springframework.springpython;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.authn.AuthenticationInterceptor;
import org.apache.directory.server.core.entry.ServerEntry;
import org.apache.directory.server.core.exception.ExceptionInterceptor;
import org.apache.directory.server.core.interceptor.Interceptor;
import org.apache.directory.server.core.normalization.NormalizationInterceptor;
import org.apache.directory.server.core.operational.OperationalAttributeInterceptor;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmPartition;
import org.apache.directory.server.core.referral.ReferralInterceptor;
import org.apache.directory.server.core.subtree.SubentryInterceptor;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.store.LdifFileLoader;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.shared.ldap.exception.LdapNameNotFoundException;
import org.apache.directory.shared.ldap.name.LdapDN;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.Lifecycle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.Assert;

/**
 * Provides lifecycle services for the embedded apacheDS server defined by the supplied configuration.
 * Used by {@link LdapServerBeanDefinitionParser}. An instance will be stored in the application context for
 * each embedded server instance. It will start the server when the context is initialized and shut it down when
 * it is closed. It is intended for temporary embedded use and will not retain changes across start/stop boundaries. The
 * working directory is deleted on shutdown.
 *
 * <p>
 * If used repeatedly in a single JVM process with the same configuration (for example, when
 * repeatedly loading an application context during testing), it's important that the
 * application context is closed to allow the bean to be disposed of and the server shutdown
 * prior to attempting to start it again.
 * <p>
 * This class is intended for testing and internal security namespace use and is not considered part of
 * framework public API.
 *
 * @author Luke Taylor
 * @version $Id: ApacheDSContainer.java 3937 2009-10-08 12:27:41Z ltaylor $
 */
public class ApacheDSContainer implements InitializingBean, DisposableBean, Lifecycle, ApplicationContextAware {
    private Log logger = LogFactory.getLog(getClass());

    DefaultDirectoryService service;
    LdapServer server;

    private ApplicationContext ctxt;
    private File workingDir;

    private boolean running;
    private String ldifResources;
    private JdbmPartition partition;
    private String root;
    private int port = 53389;

    public ApacheDSContainer(String root, String ldifs) throws Exception {
        this.ldifResources = ldifs;
        service = new DefaultDirectoryService();
        List<Interceptor> list = new ArrayList<Interceptor>();

        list.add( new NormalizationInterceptor() );
        list.add( new AuthenticationInterceptor() );
        list.add( new ReferralInterceptor() );
//        list.add( new AciAuthorizationInterceptor() );
//        list.add( new DefaultAuthorizationInterceptor() );
        list.add( new ExceptionInterceptor() );
//       list.add( new ChangeLogInterceptor() );
       list.add( new OperationalAttributeInterceptor() );
//        list.add( new SchemaInterceptor() );
        list.add( new SubentryInterceptor() );
//        list.add( new CollectiveAttributeInterceptor() );
//        list.add( new EventInterceptor() );
//        list.add( new TriggerInterceptor() );
//        list.add( new JournalInterceptor() );

        service.setInterceptors( list );
        partition =  new JdbmPartition();
        partition.setId("rootPartition");
        partition.setSuffix(root);
        this.root = root;
        service.addPartition(partition);
        service.setExitVmOnShutdown(false);
        service.setShutdownHookEnabled(false);
        service.getChangeLog().setEnabled(false);
        service.setDenormalizeOpAttrsEnabled(true);
    }

    public void afterPropertiesSet() throws Exception {
        if (workingDir == null) {
            String apacheWorkDir = System.getProperty("apacheDSWorkDir");

            if (apacheWorkDir == null) {
                apacheWorkDir = "apacheds-spring-security";
            }

            setWorkingDirectory(new File(apacheWorkDir));
        }

        server = new LdapServer();
        server.setDirectoryService(service);
        server.setTransports(new TcpTransport(port));
        start();
    }

    public void destroy() throws Exception {
        stop();
    }

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        ctxt = applicationContext;
    }

    public void setWorkingDirectory(File workingDir) {
        Assert.notNull(workingDir);

        logger.info("Setting working directory for LDAP_PROVIDER: " + workingDir.getAbsolutePath());

        if (workingDir.exists()) {
            throw new IllegalArgumentException("The specified working directory '" + workingDir.getAbsolutePath() +
                    "' already exists. Another directory service instance may be using it or it may be from a " +
                    " previous unclean shutdown. Please confirm and delete it or configure a different " +
                    "working directory");
        }

        this.workingDir = workingDir;

        service.setWorkingDirectory(workingDir);
    }

    public void setPort(int port) {
        this.port = port;
    }

    public DefaultDirectoryService getService() {
        return service;
    }

    public void start() {
        if (isRunning()) {
            return;
        }

        if (service.isStarted()) {
            throw new IllegalStateException("DirectoryService is already running.");
        }

        logger.info("Starting directory server...");
        try {
            service.startup();
            server.start();
        } catch (Exception e) {
            logger.error("Server startup failed ", e);
            return;
        }

        try {
            service.getAdminSession().lookup(partition.getSuffixDn());
        }
        catch (LdapNameNotFoundException e) {
            try {
                LdapDN dn = new LdapDN(root);
                Assert.isTrue(root.startsWith("dc="));
                String dc = root.substring(3,root.indexOf(','));
                ServerEntry entry = service.newEntry(dn);
                entry.add("objectClass", "top", "domain", "extensibleObject");
                entry.add("dc",dc);
                service.getAdminSession().add( entry );
            } catch (Exception e1) {
                logger.error("Failed to create dc entry", e1);
            }
        } catch (Exception e) {
            logger.error("Lookup failed", e);
        }

        running = true;

        try {
            importLdifs();
        } catch (Exception e) {
            logger.error("Failed to import LDIF file(s)", e);
        }
    }

    public void stop() {
        if (!isRunning()) {
            return;
        }

        logger.info("Shutting down directory server ...");
        try {
            server.stop();
            service.shutdown();
        } catch (Exception e) {
            logger.error("Shutdown failed", e);
            return;
        }

        running = false;

        if (workingDir.exists()) {
            logger.info("Deleting working directory " + workingDir.getAbsolutePath());
            deleteDir(workingDir);
        }
    }

    private void importLdifs() throws Exception {
        // Import any ldif files
        Resource[] ldifs;

        if (ctxt == null) {
            // Not running within an app context
            ldifs = new PathMatchingResourcePatternResolver().getResources(ldifResources);
        } else {
            ldifs = ctxt.getResources(ldifResources);
        }

        // Note that we can't just import using the ServerContext returned
        // from starting Apace DS, apparently because of the long-running issue DIRSERVER-169.
        // We need a standard context.
        //DirContext dirContext = contextSource.getReadWriteContext();

        if(ldifs != null && ldifs.length > 0) {
            String ldifFile = ldifs[0].getFile().getAbsolutePath();
            logger.info("Loading LDIF file: " + ldifFile);
            LdifFileLoader loader = new LdifFileLoader(service.getAdminSession(), ldifFile);
            loader.execute();
        }
    }

    private boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i=0; i < children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }

        return dir.delete();
    }

    public boolean isRunning() {
        return running;
    }

    public static void main(String[] args) throws Exception {
        ApacheDSContainer server = new ApacheDSContainer("dc=springframework,dc=org", "classpath:test-server.ldif");
        server.afterPropertiesSet();
    }
}
