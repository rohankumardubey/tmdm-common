/*
 * Copyright (C) 2006-2013 Talend Inc. - www.talend.com
 *
 * This source code is available under agreement available at
 * %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
 *
 * You should have received a copy of the agreement
 * along with this program; if not, write to Talend SA
 * 9 rue Pages 92150 Suresnes, France
 */

package org.talend.mdm.commmon.metadata;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;

import javax.xml.XMLConstants;
import java.util.*;

/**
 * Default implementation for a MDM entity type (i.e. "complex" type).
 */
public class ComplexTypeMetadataImpl extends AbstractMetadataExtensible implements ComplexTypeMetadata {

    private final String nameSpace;

    private final List<String> allowWrite;

    private final Map<String, FieldMetadata> fieldMetadata = new LinkedHashMap<String, FieldMetadata>();

    private final Map<String, FieldMetadata> keyFields = new LinkedHashMap<String, FieldMetadata>();

    private final Collection<TypeMetadata> superTypes = new LinkedList<TypeMetadata>();

    private final List<String> denyCreate;

    private final List<String> hideUsers;

    private final List<String> logicalDelete;

    private final String schematron;

    private final List<String> physicalDelete;

    private final Collection<ComplexTypeMetadata> subTypes = new HashSet<ComplexTypeMetadata>();

    private final List<FieldMetadata> lookupFields;

    private final boolean isInstantiable;

    private List<FieldMetadata> primaryKeyInfo;

    private String name;

    private MetadataRepository repository;

    private boolean isFrozen;

    public ComplexTypeMetadataImpl(String nameSpace, String name, boolean instantiable) {
        this(nameSpace,
                name,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                StringUtils.EMPTY,
                Collections.<FieldMetadata>emptyList(),
                Collections.<FieldMetadata>emptyList(),
                instantiable);
    }

    public ComplexTypeMetadataImpl(String nameSpace,
                                   String name,
                                   List<String> allowWrite,
                                   List<String> denyCreate,
                                   List<String> hideUsers,
                                   List<String> physicalDelete,
                                   List<String> logicalDelete,
                                   String schematron,
                                   List<FieldMetadata> primaryKeyInfo,
                                   List<FieldMetadata> lookupFields,
                                   boolean instantiable) {
        this.name = name;
        this.nameSpace = nameSpace;
        this.allowWrite = allowWrite;
        this.denyCreate = denyCreate;
        this.hideUsers = hideUsers;
        this.physicalDelete = physicalDelete;
        this.logicalDelete = logicalDelete;
        this.schematron = schematron;
        this.primaryKeyInfo = primaryKeyInfo;
        this.lookupFields = lookupFields;
        this.isInstantiable = instantiable;
    }

    public void addSuperType(TypeMetadata superType, MetadataRepository repository) {
        if (isFrozen) {
            throw new IllegalStateException("Type '" + name + "' is frozen and can not be modified.");
        }
        this.repository = repository;
        superTypes.add(superType);
    }

    public Collection<TypeMetadata> getSuperTypes() {
        return Collections.unmodifiableCollection(superTypes);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (isFrozen) {
            throw new IllegalStateException("Cannot change name after type was frozen.");
        }
        this.name = name;
    }

    public String getNamespace() {
        return nameSpace;
    }

    public FieldMetadata getField(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("Field name can not be null nor empty.");
        }
        StringTokenizer tokenizer = new StringTokenizer(fieldName, "/"); //$NON-NLS-1$
        String firstFieldName = tokenizer.nextToken();
        FieldMetadata currentField = fieldMetadata.get(firstFieldName);
        if (currentField == null) { // Look in super types if it wasn't found in current type.
            for (TypeMetadata superType : superTypes) {
                if (superType instanceof ComplexTypeMetadata) {
                    currentField = ((ComplexTypeMetadata) superType).getField(firstFieldName);
                    if (currentField != null) {
                        break;
                    }
                } else {
                    throw new NotImplementedException("No support for look up of fields in simple types.");
                }
            }
        }
        if (currentField == null) {
            throw new IllegalArgumentException("Type '" + getName() + "' does not own field '" + firstFieldName + "'");
        }
        if (tokenizer.hasMoreTokens()) {
            ComplexTypeMetadata currentType = (ComplexTypeMetadata) currentField.getType();
            while (tokenizer.hasMoreTokens()) {
                String currentFieldName = tokenizer.nextToken();
                currentField = currentType.getField(currentFieldName);
                if (tokenizer.hasMoreTokens()) {
                    currentType = (ComplexTypeMetadata) currentField.getType();
                }
            }
        }
        return currentField;
    }

    public boolean isInstantiable() {
        return isInstantiable;
    }

    @Override
    public boolean isFrozen() {
        return isFrozen;
    }

    @Override
    public void validate(ValidationHandler handler) {
        // Gets fields from super types.
        if (!superTypes.isEmpty()) {
            List<TypeMetadata> thisSuperTypes = new LinkedList<TypeMetadata>(superTypes);
            for (TypeMetadata superType : thisSuperTypes) {
                if (isInstantiable() == superType.isInstantiable()) {
                    if (superType instanceof ComplexTypeMetadata) {
                        Collection<FieldMetadata> thisTypeKeyFields = getKeyFields();
                        for (FieldMetadata thisTypeKeyField : thisTypeKeyFields) {
                            if (!((ComplexTypeMetadata) superType).hasField(thisTypeKeyField.getName())) {
                                handler.error(superType, "Type '" + name + "' cannot add field(s) to its key because " +
                                        "super type '" + superType.getName() + "' already defines key.",
                                        superType.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                                        superType.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                                        ValidationError.TYPE_CANNOT_OVERRIDE_SUPER_TYPE_KEY);
                            }
                        }
                    }
                }
            }
        }
        // Validate all fields.
        for (FieldMetadata value : fieldMetadata.values()) {
            value.validate(handler);
        }
        for (FieldMetadata keyField : keyFields.values()) {
            if (keyField.isMany()) {
                handler.error(keyField,
                        "Key field cannot be a repeatable element.",
                        keyField.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                        keyField.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                        ValidationError.FIELD_KEY_CANNOT_BE_REPEATABLE);
            }
        }
        // Validate primary info
        for (FieldMetadata pkInfo : primaryKeyInfo) {
            // PK Info must be defined in the entity (can't reference other entity field).
            if (!this.equals(pkInfo.getContainingType())) {
                handler.error(pkInfo,
                        "Primary key info must refer a field of the same entity.",
                        pkInfo.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                        pkInfo.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                        ValidationError.PRIMARY_KEY_INFO_NOT_IN_ENTITY);
                continue;
            }
            // Order matters here: check if field is correct (exists) before checking isMany().
            int previousErrorCount = handler.getErrorCount();
            pkInfo.validate(handler);
            // No need to check isMany() if field definition is already wrong.
            if (handler.getErrorCount() == previousErrorCount && pkInfo.isMany()) {
                handler.error(pkInfo,
                        "Primary key info element cannot be a repeatable element.",
                        pkInfo.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                        pkInfo.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                        ValidationError.PRIMARY_KEY_INFO_CANNOT_BE_REPEATABLE);
            }
        }
        // Validate lookup fields
        for (FieldMetadata lookupField : lookupFields) {
            int errorCount = handler.getErrorCount();
            lookupField.validate(handler);
            if (handler.getErrorCount() > errorCount) {
                continue;
            }
            // Lookup field must be defined in the entity (can't reference other entity field).
            if (!this.equals(lookupField.getContainingType())) {
                handler.error(lookupField,
                        "Lookup field info must refer a field of the same entity.",
                        lookupField.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                        lookupField.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                        ValidationError.LOOKUP_FIELD_NOT_IN_ENTITY);
                continue;
            }
            if (lookupField.isKey()) {
                handler.error(lookupField,
                        "Lookup field cannot be in entity key.",
                        lookupField.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                        lookupField.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                        ValidationError.LOOKUP_FIELD_CANNOT_BE_KEY);
                continue;
            }
            // Order matters here: check if field is correct (exists) before checking isMany().
            lookupField.validate(handler);
            TypeMetadata currentType = lookupField.getType();
            if (!XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(currentType.getNamespace())) {
                while (!currentType.getSuperTypes().isEmpty()) {
                    TypeMetadata superType = currentType.getSuperTypes().iterator().next();
                    if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(superType.getNamespace())
                            && ("anyType".equals(superType.getName()) //$NON-NLS-1$
                            || "anySimpleType".equals(superType.getName()))) { //$NON-NLS-1$
                        break;
                    }
                    currentType = superType;
                }
            }
            if (!XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(currentType.getNamespace())) {
                handler.error(lookupField,
                        "Lookup field must be a simple typed element.",
                        lookupField.<Integer>getData(MetadataRepository.XSD_LINE_NUMBER),
                        lookupField.<Integer>getData(MetadataRepository.XSD_COLUMN_NUMBER),
                        ValidationError.LOOKUP_FIELD_MUST_BE_SIMPLE_TYPE);
            }
        }
    }

    public Collection<FieldMetadata> getKeyFields() {
        return Collections.unmodifiableCollection(keyFields.values());
    }

    public Collection<FieldMetadata> getFields() {
        if (!isFrozen) {
            throw new IllegalStateException("Type should be frozen before calling this method.");
        }
        return Collections.unmodifiableCollection(fieldMetadata.values());
    }

    public boolean isAssignableFrom(TypeMetadata type) {
        // Check one level of inheritance
        Collection<TypeMetadata> superTypes = getSuperTypes();
        for (TypeMetadata superType : superTypes) {
            if (type.getName().equals(superType.getName())) {
                return true;
            }
        }

        // Checks in type inheritance hierarchy.
        for (TypeMetadata superType : superTypes) {
            if (superType.isAssignableFrom(type)) {
                return true;
            }
        }

        return getName().equals(type.getName());
    }

    public <T> T accept(MetadataVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return '[' + nameSpace + ':' + name + ']';
    }

    public void addField(FieldMetadata fieldMetadata) {
        if (isFrozen) {
            throw new IllegalStateException("Type '" + name + "' is frozen and can not be modified.");
        }
        if (fieldMetadata == null) {
            throw new IllegalArgumentException("Field can not be null.");
        }
        this.fieldMetadata.put(fieldMetadata.getName(), fieldMetadata);
        if (fieldMetadata.isKey()) {
            registerKey(fieldMetadata);
        }
    }

    public void registerKey(FieldMetadata keyField) {
        if (keyField == null) {
            throw new IllegalArgumentException("Key field can not be null.");
        }
        keyFields.put(keyField.getName(), keyField);
    }

    public ComplexTypeMetadata copy(MetadataRepository repository) {
        ComplexTypeMetadata registeredCopy;
        if (isInstantiable) {
            registeredCopy = repository.getComplexType(getName());
            if (registeredCopy != null) {
                return registeredCopy;
            }
        } else {
            registeredCopy = (ComplexTypeMetadata) repository.getNonInstantiableType(repository.getUserNamespace(), getName());
            if (registeredCopy != null) {
                return registeredCopy;
            }
        }

        ComplexTypeMetadataImpl copy = new ComplexTypeMetadataImpl(getNamespace(),
                getName(),
                allowWrite,
                denyCreate,
                hideUsers,
                physicalDelete,
                logicalDelete,
                schematron,
                primaryKeyInfo, Collections.<FieldMetadata>emptyList(), isInstantiable);
        repository.addTypeMetadata(copy);

        Collection<FieldMetadata> fields = getFields();
        for (FieldMetadata field : fields) {
            copy.addField(field.copy(repository));
        }
        for (TypeMetadata superType : superTypes) {
            copy.addSuperType(superType.copy(repository), repository);
        }

        Collection<FieldMetadata> typeKeyFields = getKeyFields();
        for (FieldMetadata typeKeyField : typeKeyFields) {
            copy.registerKey(typeKeyField.copy(repository));
        }

        return copy;
    }

    public TypeMetadata copyShallow() {
        return new ComplexTypeMetadataImpl(getNamespace(),
                getName(),
                allowWrite,
                denyCreate,
                hideUsers,
                physicalDelete,
                logicalDelete,
                schematron,
                primaryKeyInfo,
                Collections.<FieldMetadata>emptyList(), isInstantiable);
    }

    public List<String> getWriteUsers() {
        return allowWrite;
    }

    public List<String> getDenyCreate() {
        return denyCreate;
    }

    public List<String> getHideUsers() {
        return hideUsers;
    }

    public List<String> getDenyDelete(DeleteType type) {
        switch (type) {
            case LOGICAL:
                return logicalDelete;
            case PHYSICAL:
                return physicalDelete;
            default:
                throw new NotImplementedException("Security information parsing for delete type '" + type + "'");
        }
    }

    public String getSchematron() {
        return schematron;
    }

    @Override
    public List<FieldMetadata> getPrimaryKeyInfo() {
        return primaryKeyInfo;
    }

    @Override
    public List<FieldMetadata> getLookupFields() {
        return lookupFields;
    }

    public boolean hasField(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        if (fieldName.indexOf('/') < 0) {
            boolean contains = fieldMetadata.containsKey(fieldName);
            if (!contains) {
                for (TypeMetadata typeMetadata : getSuperTypes()) {
                    contains |= ((ComplexTypeMetadata) typeMetadata).hasField(fieldName);
                }
            }
            return contains;
        }
        StringTokenizer tokenizer = new StringTokenizer(fieldName, "/"); //$NON-NLS-1$
        ComplexTypeMetadata currentType = this;
        while (tokenizer.hasMoreTokens()) {
            String current = tokenizer.nextToken();
            if (!currentType.hasField(current)) {
                return false;
            }
            if (tokenizer.hasMoreTokens()) {
                TypeMetadata type = currentType.getField(current).getType();
                if (!(type instanceof ComplexTypeMetadata)) {
                    return false;
                }
                currentType = (ComplexTypeMetadata) type;
            }
        }
        return true;
    }

    public Collection<ComplexTypeMetadata> getSubTypes() {
        List<ComplexTypeMetadata> subTypes = new LinkedList<ComplexTypeMetadata>();
        for (ComplexTypeMetadata subType : this.subTypes) {
            subTypes.add(subType);
            subTypes.addAll(subType.getSubTypes());
        }
        return subTypes;
    }

    public void registerSubType(ComplexTypeMetadata type) {
        subTypes.add(type);
    }

    public TypeMetadata freeze(ValidationHandler handler) {
        if (isFrozen) {
            return this;
        }
        // Gets fields from super types.
        if (!superTypes.isEmpty()) {
            Collection<FieldMetadata> thisTypeFields = new LinkedList<FieldMetadata>(fieldMetadata.values());
            fieldMetadata.clear();
            List<TypeMetadata> thisSuperTypes = new LinkedList<TypeMetadata>(superTypes);
            superTypes.clear();
            for (TypeMetadata superType : thisSuperTypes) {
                if (isInstantiable() == superType.isInstantiable()) {
                    superType = superType.freeze(handler);
                    if (superType instanceof ComplexTypeMetadata) {
                        ((ComplexTypeMetadata) superType).registerSubType(this);
                    }
                    superTypes.add(superType);
                } else {
                    superType = superType.freeze(handler);
                }
                if (superType instanceof ComplexTypeMetadata) {
                    ((ComplexTypeMetadata) superType).registerSubType(this);
                    Collection<FieldMetadata> superTypeFields = ((ComplexTypeMetadata) superType).getFields();
                    for (FieldMetadata superTypeField : superTypeFields) {
                        superTypeField.adopt(this, repository);
                    }
                }
            }
            for (FieldMetadata thisTypeField : thisTypeFields) {
                fieldMetadata.put(thisTypeField.getName(), thisTypeField);
            }
        }
        isFrozen = true;
        // Freeze all fields.
        Collection<FieldMetadata> values = new LinkedList<FieldMetadata>(fieldMetadata.values());
        for (FieldMetadata value : values) {
            try {
                FieldMetadata frozenFieldDeclaration = value.freeze(handler);
                fieldMetadata.put(value.getName(), frozenFieldDeclaration);
                if (keyFields.containsKey(value.getName()) && !frozenFieldDeclaration.isKey()) {
                    frozenFieldDeclaration.promoteToKey();
                }
            } catch (Exception e) {
                throw new RuntimeException("Could not process field '" + value.getName() + "' in type '" + getName() + "'", e);
            }
        }
        for (Map.Entry<String, FieldMetadata> keyField : keyFields.entrySet()) {
            keyField.setValue(keyField.getValue().freeze(handler));
        }
        // Freeze primary info
        List<FieldMetadata> frozenPrimaryKeyInfo = new LinkedList<FieldMetadata>();
        for (FieldMetadata pkInfo : primaryKeyInfo) {
            frozenPrimaryKeyInfo.add(pkInfo.freeze(handler));
        }
        primaryKeyInfo = frozenPrimaryKeyInfo;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ComplexTypeMetadata)) return false;
        ComplexTypeMetadata that = (ComplexTypeMetadata) o;
        return that.getName().equals(name) && that.getNamespace().equals(nameSpace);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + nameSpace.hashCode();
        return result;
    }
}