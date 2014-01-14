/* 
 * Hibernate, Relational Persistence for Idiomatic Java
 * 
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.hibernate.osgi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.persistence.PersistenceException;

import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.jpa.boot.archive.spi.ArchiveContext;
import org.hibernate.jpa.boot.archive.spi.ArchiveDescriptor;
import org.hibernate.jpa.boot.archive.spi.ArchiveEntry;
import org.hibernate.jpa.boot.spi.InputStreamAccess;
import org.hibernate.jpa.boot.spi.NamedInputStream;

import org.hibernate.jpa.internal.EntityManagerMessageLogger;
import org.jboss.logging.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleWiring;

/**
 * ArchiveDescriptor implementation for describing archives in the OSGi sense
 *
 * @author Brett Meyer
 * @author Tim Ward
 */
public class OsgiArchiveDescriptor implements ArchiveDescriptor {
	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( OsgiArchiveDescriptor.class );
    private static final EntityManagerMessageLogger log = Logger.getMessageLogger(
                                                                EntityManagerMessageLogger.class,
                                                                OsgiArchiveDescriptor.class.getName()
    );

    private class BundleAndWiring {
        private final Bundle       persistenceBundle;
        private final BundleWiring bundleWiring;

        public BundleAndWiring(Bundle persistenceBundle) {
            this.persistenceBundle = persistenceBundle;
            bundleWiring = (BundleWiring) persistenceBundle.adapt( BundleWiring.class );
        }

        private Bundle getPersistenceBundle() {
            return persistenceBundle;
        }

        private BundleWiring getBundleWiring() {
            return bundleWiring;
        }
    }
    private final Set<BundleAndWiring> bundleAndWirings = new HashSet<BundleAndWiring>();


	/**
	 * Creates a OsgiArchiveDescriptor
	 *
	 * @param persistenceBundles The bundles being described as an archive
	 */
	@SuppressWarnings("RedundantCast")
	public OsgiArchiveDescriptor(Set<Bundle> persistenceBundles) {
        for (Bundle persistenceBundle: persistenceBundles) {
            BundleAndWiring bundleAndWiring = new BundleAndWiring(persistenceBundle);
            bundleAndWirings.add(bundleAndWiring);
        }
	}

	@Override
	public void visitArchive(ArchiveContext context) {
        for (final BundleAndWiring bundleAndWiring : bundleAndWirings) {
            log.debug("visit archive for bundle {} ..." + bundleAndWiring.getPersistenceBundle().getSymbolicName());
            Collection<String> resources = bundleAndWiring.getBundleWiring().listResources( "/", "*", BundleWiring.LISTRESOURCES_RECURSE );
            if (resources!=null) {
                for ( final String resource : resources ) {
                    // TODO: Is there a better way to check this?  Karaf is including directories.
                    if ( !resource.endsWith( "/" ) ) {
                        try {
                            // TODO: Is using resource as the names correct?
                            final InputStreamAccess inputStreamAccess = new InputStreamAccess() {
                                @Override
                                public String getStreamName() {
                                    return resource;
                                }

                                @Override
                                public InputStream accessInputStream() {
                                    return openInputStream();
                                }

                                @Override
                                public NamedInputStream asNamedInputStream() {
                                    return new NamedInputStream( resource, openInputStream() );
                                }

                                private InputStream openInputStream() {
                                    try {
                                        return bundleAndWiring.getPersistenceBundle().getResource( resource ).openStream();
                                    }
                                    catch ( IOException e ) {
                                        throw new PersistenceException(
                                                "Unable to open an InputStream on the OSGi Bundle resource!",
                                                e );
                                    }
                                }

                            };

                            final ArchiveEntry entry = new ArchiveEntry() {
                                @Override
                                public String getName() {
                                    return resource;
                                }

                                @Override
                                public String getNameWithinArchive() {
                                    return resource;
                                }

                                @Override
                                public InputStreamAccess getStreamAccess() {
                                    return inputStreamAccess;
                                }
                            };

                            context.obtainArchiveEntryHandler( entry ).handleEntry( entry, context );
                        }
                        catch ( Exception e ) {
                            LOG.unableToLoadScannedClassOrResource( e );
                        }
                    }
                }
            } else {
                log.debug("No resources on bundle "  + bundleAndWiring.getPersistenceBundle().getSymbolicName() + ". What's the fuck ?!");
            }
        }
	}

}
