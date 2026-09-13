package ar.edu.utn.frc.tup.p4.usersservice.shared.audit;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import org.hibernate.HibernateException;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class AuditHibernateListener
        implements PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    private final Clock clock;
    private final Map<String, AuditMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Object transactionResourceKey = new Object();

    public AuditHibernateListener(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        if (!isAudited(event.getPersister())) {
            return false;
        }
        requireActiveTransaction(event.getSession().isTransactionInProgress(), "inserts");

        BaseAuditableEntity entity = auditedEntity(event.getEntity());
        Instant now = clock.instant();
        AuditActor actor = currentActor();
        String traceId = currentTraceId();
        entity.initializeAuditFields(now, actor.userId(), actor.serviceId(), traceId);
        setState(event.getPersister(), event.getState(), "createdAt", now);
        setState(event.getPersister(), event.getState(), "createdUser", actor.userId());
        setState(event.getPersister(), event.getState(), "createdService", actor.serviceId());
        setState(event.getPersister(), event.getState(), "createdTraceId", traceId);
        setState(event.getPersister(), event.getState(), "updatedAt", now);
        setState(event.getPersister(), event.getState(), "lastUpdatedUser", actor.userId());
        setState(event.getPersister(), event.getState(), "lastUpdatedService", actor.serviceId());
        setState(event.getPersister(), event.getState(), "lastUpdatedTraceId", traceId);
        setState(event.getPersister(), event.getState(), "lockVersion", 0L);
        transactionStateForCurrentTransaction().insertedEntities().add(
                new AuditKey(event.getPersister().getEntityName(), event.getId()));
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        if (!isAudited(event.getPersister())) {
            return false;
        }
        requireActiveTransaction(event.getSession().isTransactionInProgress(), "updates");

        AuditKey key = new AuditKey(event.getPersister().getEntityName(), event.getId());
        TransactionAuditState transactionState = transactionStateForCurrentTransaction();
        if (!transactionState.insertedEntities().contains(key)
                && !transactionState.archivedEntities().contains(key)) {
            archiveCurrentState(event);
            transactionState.archivedEntities().add(key);
        }

        BaseAuditableEntity entity = auditedEntity(event.getEntity());
        Instant now = clock.instant();
        AuditActor actor = currentActor();
        String traceId = currentTraceId();
        entity.updateAuditFields(now, actor.userId(), actor.serviceId(), traceId);
        setState(event.getPersister(), event.getState(), "updatedAt", now);
        setState(event.getPersister(), event.getState(), "lastUpdatedUser", actor.userId());
        setState(event.getPersister(), event.getState(), "lastUpdatedService", actor.serviceId());
        setState(event.getPersister(), event.getState(), "lastUpdatedTraceId", traceId);
        return false;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        if (isAudited(event.getPersister())) {
            throw new HibernateException(
                    "Physical deletion is forbidden for audited entity "
                            + event.getPersister().getEntityName());
        }
        return false;
    }

    private void archiveCurrentState(PreUpdateEvent event) {
        AuditMetadata metadata = metadataCache.computeIfAbsent(
                event.getPersister().getEntityName(),
                ignored -> resolveMetadata(event.getPersister()));

        event.getSession().doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(metadata.insertSelectSql())) {
                statement.setString(1, event.getId().toString());
                int affectedRows = statement.executeUpdate();
                if (affectedRows != 1) {
                    throw new HibernateException(
                            "Audit copy expected one row for "
                                    + event.getPersister().getEntityName()
                                    + " but copied " + affectedRows);
                }
            }
        });
    }

    private AuditMetadata resolveMetadata(EntityPersister persister) {
        Class<?> entityClass = persister.getMappedClass();
        AuditedTable auditedTable = entityClass.getAnnotation(AuditedTable.class);
        String mainTable = validateIdentifier(persister.getMappedTableDetails().getTableName());
        String auditTable = auditedTable.auditTable().isBlank()
                ? mainTable + "_audit"
                : validateIdentifier(auditedTable.auditTable());
        Set<String> excludedProperties = excludedProperties(entityClass);
        LinkedHashSet<String> columns = new LinkedHashSet<>();

        if (!(persister.getIdentifierMapping() instanceof BasicValuedModelPart identifier)) {
            throw new HibernateException("Audited entities require a simple identifier");
        }
        addColumn(columns, identifier, mainTable);

        persister.forEachAttributeMapping(attribute ->
                addAttributeColumns(columns, attribute, excludedProperties, mainTable));

        if (!columns.contains("id") || !columns.contains("lock_version")) {
            throw new HibernateException(
                    "Audited entity metadata must contain id and lock_version: "
                            + persister.getEntityName());
        }

        List<String> immutableColumns = List.copyOf(columns);
        String columnList = String.join(", ", immutableColumns);
        String sql = "INSERT INTO " + auditTable + " (" + columnList + ") "
                + "SELECT " + columnList + " FROM " + mainTable + " WHERE id = ?";
        return new AuditMetadata(sql);
    }

    private void addAttributeColumns(
            Set<String> columns,
            AttributeMapping attribute,
            Set<String> excludedProperties,
            String mainTable) {
        if (attribute.isPluralAttributeMapping()
                || excludedProperties.contains(attribute.getAttributeName())) {
            return;
        }
        if (!(attribute instanceof BasicValuedModelPart basicAttribute)) {
            throw new HibernateException(
                    "Unsupported audited attribute " + attribute.getAttributeName());
        }
        addColumn(columns, basicAttribute, mainTable);
    }

    private void addColumn(
            Set<String> columns,
            BasicValuedModelPart selectable,
            String mainTable) {
        if (selectable.isFormula()) {
            return;
        }
        String containingTable = validateIdentifier(selectable.getContainingTableExpression());
        if (!mainTable.equals(containingTable)) {
            throw new HibernateException("Audited entities must use a single physical table");
        }
        columns.add(validateIdentifier(selectable.getSelectionExpression()));
    }

    private Set<String> excludedProperties(Class<?> entityClass) {
        Set<String> excluded = new HashSet<>();
        for (Class<?> type = entityClass; type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(AuditExclude.class)) {
                    excluded.add(field.getName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(AuditExclude.class)) {
                    excluded.add(propertyName(method));
                }
            }
        }
        return Set.copyOf(excluded);
    }

    private String propertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Introspector.decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2) {
            return Introspector.decapitalize(name.substring(2));
        }
        throw new HibernateException(
                "@AuditExclude method must be a JavaBean getter: " + method);
    }

    private void requireActiveTransaction(boolean hibernateTransactionActive, String operation) {
        if (!hibernateTransactionActive
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new HibernateException(
                    "Audited " + operation + " require an active transaction");
        }
    }

    private TransactionAuditState transactionStateForCurrentTransaction() {
        Object resource = TransactionSynchronizationManager.getResource(transactionResourceKey);
        if (resource != null) {
            return (TransactionAuditState) resource;
        }

        TransactionAuditState transactionState =
                new TransactionAuditState(new HashSet<>(), new HashSet<>());
        TransactionSynchronizationManager.bindResource(transactionResourceKey, transactionState);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
            }
        });
        return transactionState;
    }

    private void setState(
            EntityPersister persister,
            Object[] state,
            String propertyName,
            Object value) {
        int index = Arrays.asList(persister.getPropertyNames()).indexOf(propertyName);
        if (index < 0) {
            throw new HibernateException(
                    "Missing audited property " + propertyName
                            + " in " + persister.getEntityName());
        }
        state[index] = value;
    }

    private AuditActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof GatewayPrincipal principal) {
            if (principal.isPerson()) {
                return new AuditActor(principal.id(), null);
            }
            return new AuditActor(null, principal.serviceId());
        }
        return new AuditActor(null, null);
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null && TRACE_ID.matcher(traceId).matches() ? traceId : null;
    }

    private boolean isAudited(EntityPersister persister) {
        return persister.getMappedClass().isAnnotationPresent(AuditedTable.class);
    }

    private BaseAuditableEntity auditedEntity(Object entity) {
        if (entity instanceof BaseAuditableEntity auditedEntity) {
            return auditedEntity;
        }
        throw new HibernateException("@AuditedTable entity must extend BaseAuditableEntity");
    }

    private String validateIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER.matcher(identifier).matches()) {
            throw new HibernateException("Invalid audit SQL identifier: " + identifier);
        }
        return identifier;
    }

    private record AuditKey(String entityName, Object id) {
    }

    private record AuditActor(UUID userId, String serviceId) {
    }

    private record TransactionAuditState(
            Set<AuditKey> insertedEntities,
            Set<AuditKey> archivedEntities) {
    }

    private record AuditMetadata(String insertSelectSql) {
    }
}
