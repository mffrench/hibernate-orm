/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.osgi;

import java.util.*;

import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceException;
import javax.persistence.spi.PersistenceUnitInfo;

import org.hibernate.boot.registry.selector.StrategyRegistrationProvider;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.jpa.boot.internal.PersistenceXmlParser;
import org.hibernate.jpa.boot.spi.*;
import org.hibernate.metamodel.spi.TypeContributor;
import org.jboss.logging.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;

/**
 * Acts as the PersistenceProvider service in OSGi environments
 *
 * @author Brett Meyer
 * @author Tim Ward
 */
public class OsgiPersistenceProvider extends HibernatePersistenceProvider {

    private static final Logger log = Logger.getLogger( OsgiPersistenceProvider.class );

	private OsgiClassLoader osgiClassLoader;
	private OsgiJtaPlatform osgiJtaPlatform;
	private OsgiServiceUtil osgiServiceUtil;
	private Bundle          requestingBundle; //OsgiPersistenceProvider bundle consumer : the master persistence bundle

	/**
	 * Constructs a OsgiPersistenceProvider
	 *
	 * @param osgiClassLoader The ClassLoader we built from OSGi Bundles
	 * @param osgiJtaPlatform The OSGi-specific JtaPlatform impl we built
     * @param osgiServiceUtil
	 * @param requestingBundle The OSGi Bundle requesting the PersistenceProvider
	 */
	public OsgiPersistenceProvider(
			OsgiClassLoader osgiClassLoader,
			OsgiJtaPlatform osgiJtaPlatform,
			OsgiServiceUtil osgiServiceUtil,
			Bundle requestingBundle) {
		this.osgiClassLoader = osgiClassLoader;
		this.osgiJtaPlatform = osgiJtaPlatform;
		this.osgiServiceUtil = osgiServiceUtil;
		this.requestingBundle = requestingBundle;
	}

	// TODO: Does "hibernate.classloaders" and osgiClassLoader need added to the
	// EMFBuilder somehow?

	@Override
	@SuppressWarnings("unchecked")
	public EntityManagerFactory createEntityManagerFactory(String persistenceUnitName, Map properties) {
		final Map settings = generateSettings( properties );

		// TODO: This needs tested.
        if (!settings.containsKey(org.hibernate.jpa.AvailableSettings.SCANNER))
		    settings.put( org.hibernate.jpa.AvailableSettings.SCANNER, new OsgiScanner(requestingBundle) );
		// TODO: This is temporary -- for PersistenceXmlParser's use of
		// ClassLoaderServiceImpl#fromConfigSettings
        settings.put( AvailableSettings.ENVIRONMENT_CLASSLOADER, osgiClassLoader );
        osgiClassLoader.addBundle(requestingBundle);

		final EntityManagerFactoryBuilder builder = getEntityManagerFactoryBuilderForMultipleBundleOrNull( persistenceUnitName, settings, osgiClassLoader );
		return builder == null ? null : builder.build();
	}

	@Override
	@SuppressWarnings("unchecked")
	public EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo info, Map properties) {
		final Map settings = generateSettings( properties );

		// OSGi ClassLoaders must implement BundleReference
		settings.put(
				org.hibernate.jpa.AvailableSettings.SCANNER,
				new OsgiScanner(( (BundleReference) info.getClassLoader() ).getBundle())
		);

		osgiClassLoader.addClassLoader( info.getClassLoader() );

		return Bootstrap.getEntityManagerFactoryBuilder( info, settings, osgiClassLoader ).build();
	}

    @Override
    public boolean generateSchema(String persistenceUnitName, Map map) {
        log.tracef( "Starting generateSchema for persistenceUnitName %s", persistenceUnitName );
        final Map settings = generateSettings( map );
        ArrayList<ClassLoader> classLoaders =  new ArrayList<ClassLoader>();classLoaders.add(osgiClassLoader);
        settings.put( AvailableSettings.CLASSLOADERS,classLoaders);
        final EntityManagerFactoryBuilder builder = getEntityManagerFactoryBuilderForMultipleBundleOrNull(persistenceUnitName, settings, osgiClassLoader);
        if ( builder == null ) {
            log.trace( "Could not obtain matching EntityManagerFactoryBuilder, returning false" );
            return false;
        }
        builder.generateSchema();
        return true;
    }

	@SuppressWarnings("unchecked")
	private Map generateSettings(Map properties) {
		final Map settings = new HashMap();
		if ( properties != null ) {
			settings.putAll( properties );
		}

		settings.put( AvailableSettings.JTA_PLATFORM, osgiJtaPlatform );

		final Integrator[] integrators = osgiServiceUtil.getServiceImpls( Integrator.class );
		final IntegratorProvider integratorProvider = new IntegratorProvider() {
			@Override
			public List<Integrator> getIntegrators() {
				return Arrays.asList( integrators );
			}
		};
		settings.put( EntityManagerFactoryBuilderImpl.INTEGRATOR_PROVIDER, integratorProvider );

		final StrategyRegistrationProvider[] strategyRegistrationProviders = osgiServiceUtil.getServiceImpls(
				StrategyRegistrationProvider.class );
		final StrategyRegistrationProviderList strategyRegistrationProviderList = new StrategyRegistrationProviderList() {
			@Override
			public List<StrategyRegistrationProvider> getStrategyRegistrationProviders() {
				return Arrays.asList( strategyRegistrationProviders );
			}
		};
		settings.put( EntityManagerFactoryBuilderImpl.STRATEGY_REGISTRATION_PROVIDERS, strategyRegistrationProviderList );

		final TypeContributor[] typeContributors = osgiServiceUtil.getServiceImpls( TypeContributor.class );
		final TypeContributorList typeContributorList = new TypeContributorList() {
			@Override
			public List<TypeContributor> getTypeContributors() {
				return Arrays.asList( typeContributors );
			}
		};
		settings.put( EntityManagerFactoryBuilderImpl.TYPE_CONTRIBUTORS, typeContributorList );
		
		return settings;
	}

    protected EntityManagerFactoryBuilder getEntityManagerFactoryBuilderForMultipleBundleOrNull(String persistenceUnitName, Map properties, ClassLoader providedClassLoader) {
        log.tracef( "Attempting to obtain correct EntityManagerFactoryBuilder for persistenceUnitName : %s", persistenceUnitName );

        final Map integration = (properties == null ? Collections.emptyMap() : Collections.unmodifiableMap( properties ));
        final List<ParsedPersistenceXmlDescriptor> units;
        try {
            units = PersistenceXmlParser.locatePersistenceUnits(integration);
        }
        catch (Exception e) {
            log.debug( "Unable to locate persistence units", e );
            throw new PersistenceException( "Unable to locate persistence units", e );
        }

        log.debugf( "Located and parsed %s persistence units; checking each", units.size() );

        if ( persistenceUnitName == null && units.size() > 1 ) {
            // no persistence-unit name to look for was given and we found multiple persistence-units
            throw new PersistenceException( "No name provided and multiple persistence units found" );
        }

        ParsedPersistenceXmlDescriptor finalPersistenceUnit = null;
        for ( ParsedPersistenceXmlDescriptor persistenceUnit : units ) {
            log.debugf(
                              "Checking persistence-unit [name=%s, explicit-provider=%s] against incoming persistence unit name [%s]",
                              persistenceUnit.getName(),
                              persistenceUnit.getProviderClassName(),
                              persistenceUnitName
            );

            final boolean matches = persistenceUnitName == null || persistenceUnit.getName().equals( persistenceUnitName );
            if ( !matches ) {
                log.debug( "Excluding from consideration due to name mis-match" );
                continue;
            }

            // See if we (Hibernate) are the persistence provider
            if ( ! ProviderChecker.isProvider(persistenceUnit, properties) ) {
                log.debug( "Excluding from consideration due to provider mis-match" );
                continue;
            }

            if (finalPersistenceUnit != null) {
                finalPersistenceUnit.addClasses(persistenceUnit.getManagedClassNames());
            } else {
                finalPersistenceUnit = persistenceUnit;
            }
        }

        if (finalPersistenceUnit != null) {
            return Bootstrap.getEntityManagerFactoryBuilder( finalPersistenceUnit, integration, providedClassLoader );
        } else {
            log.debug( "Found no matching persistence units" );
            return null;
        }
    }
}
