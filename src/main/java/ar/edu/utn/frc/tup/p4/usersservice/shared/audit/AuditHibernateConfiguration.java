package ar.edu.utn.frc.tup.p4.usersservice.shared.audit;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AuditHibernateConfiguration {

    @Bean
    Clock auditClock() {
        return Clock.systemUTC();
    }

    @Bean
    InitializingBean auditHibernateListenerRegistration(
            EntityManagerFactory entityManagerFactory,
            AuditHibernateListener listener) {
        return () -> {
            SessionFactoryImplementor sessionFactory =
                    entityManagerFactory.unwrap(SessionFactoryImplementor.class);
            EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                    .getService(EventListenerRegistry.class);
            registry.appendListeners(EventType.PRE_INSERT, listener);
            registry.appendListeners(EventType.PRE_UPDATE, listener);
            registry.appendListeners(EventType.PRE_DELETE, listener);
        };
    }
}
