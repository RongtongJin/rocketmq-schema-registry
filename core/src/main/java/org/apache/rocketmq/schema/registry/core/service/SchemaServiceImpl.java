/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.schema.registry.core.service;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.schema.registry.common.QualifiedName;
import org.apache.rocketmq.schema.registry.common.auth.AccessControlService;
import org.apache.rocketmq.schema.registry.common.context.RequestContext;
import org.apache.rocketmq.schema.registry.common.context.RequestContextManager;
import org.apache.rocketmq.schema.registry.common.dto.RegisterSchemaRequest;
import org.apache.rocketmq.schema.registry.common.dto.RegisterSchemaResponse;
import org.apache.rocketmq.schema.registry.common.dto.SchemaDto;
import org.apache.rocketmq.schema.registry.common.dto.SchemaRecordDto;
import org.apache.rocketmq.schema.registry.common.dto.UpdateSchemaRequest;
import org.apache.rocketmq.schema.registry.common.dto.UpdateSchemaResponse;
import org.apache.rocketmq.schema.registry.common.exception.SchemaCompatibilityException;
import org.apache.rocketmq.schema.registry.common.exception.SchemaException;
import org.apache.rocketmq.schema.registry.common.exception.SchemaExistException;
import org.apache.rocketmq.schema.registry.common.exception.SchemaNotFoundException;
import org.apache.rocketmq.schema.registry.common.model.AuditInfo;
import org.apache.rocketmq.schema.registry.common.model.Dependency;
import org.apache.rocketmq.schema.registry.common.model.SchemaDetailInfo;
import org.apache.rocketmq.schema.registry.common.model.SchemaInfo;
import org.apache.rocketmq.schema.registry.common.model.SchemaMetaInfo;
import org.apache.rocketmq.schema.registry.common.model.SchemaOperation;
import org.apache.rocketmq.schema.registry.common.model.SchemaRecordInfo;
import org.apache.rocketmq.schema.registry.common.properties.GlobalConfig;
import org.apache.rocketmq.schema.registry.common.storage.StorageServiceProxy;
import org.apache.rocketmq.schema.registry.common.utils.CommonUtil;
import org.apache.rocketmq.schema.registry.common.utils.IdGenerator;
import org.apache.rocketmq.schema.registry.common.utils.StorageUtil;
import org.apache.rocketmq.schema.registry.core.dependency.DependencyService;

@Slf4j
public class SchemaServiceImpl implements SchemaService<SchemaDto> {

    private final GlobalConfig config;

    private final AccessControlService accessController;
    private final StorageServiceProxy storageServiceProxy;
    private final StorageUtil storageUtil;

    private final DependencyService dependencyService;

    private final IdGenerator idGenerator;

    public SchemaServiceImpl(
        final GlobalConfig config,
        final AccessControlService accessController,
        final StorageServiceProxy storageServiceProxy,
        final StorageUtil storageUtil,
        final DependencyService dependencyService,
        final IdGenerator idGenerator
    ) {
        this.config = config;
        this.accessController = accessController;
        this.storageServiceProxy = storageServiceProxy;
        this.storageUtil = storageUtil;
        this.dependencyService = dependencyService;
        this.idGenerator = idGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RegisterSchemaResponse register(QualifiedName qualifiedName, RegisterSchemaRequest registerSchemaDto) {
        final RequestContext requestContext = RequestContextManager.getContext();
        log.info("register get request context: " + requestContext);

        // TODO: add user and ak sk
        accessController.checkPermission("", qualifiedName.getTenant(), SchemaOperation.REGISTER);

        checkSchemaExist(qualifiedName);

        final AuditInfo audit = new AuditInfo();
        audit.createBy(registerSchemaDto.getOwner(), registerSchemaDto.getDesc());

        long schemaId = idGenerator.nextId();
        final SchemaMetaInfo meta = new SchemaMetaInfo();
        meta.setCompatibility(registerSchemaDto.getCompatibility());
        meta.setOwner(registerSchemaDto.getOwner());
        meta.setType(registerSchemaDto.getSchemaType());
        meta.setSchemaName(qualifiedName.getSchema());
        meta.setTenant(qualifiedName.getTenant());
        meta.setUniqueId(schemaId);

        final SchemaRecordInfo firstRecord = new SchemaRecordInfo();
        firstRecord.setSchema(qualifiedName.schemaFullName());
        firstRecord.setSchemaId(schemaId);
        firstRecord.setType(registerSchemaDto.getSchemaType());
        firstRecord.setIdl(registerSchemaDto.getSchemaIdl());
        firstRecord.bindSubject(qualifiedName.subjectInfo());

        final SchemaDetailInfo details = new SchemaDetailInfo(firstRecord);
        final SchemaInfo schemaInfo = new SchemaInfo(qualifiedName, audit, meta, details);

        if (config.isUploadEnabled()) {
            // TODO: async upload to speed up register operation and keep atomic with register
            Dependency dependency = dependencyService.compile(schemaInfo);
            schemaInfo.setLastRecordDependency(dependency);
        }

        log.info("Creating schema info {}: {}", qualifiedName, schemaInfo);
        storageServiceProxy.register(qualifiedName, schemaInfo);
        return new RegisterSchemaResponse(schemaInfo.getUniqueId(), schemaInfo.getLastRecordVersion());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UpdateSchemaResponse update(QualifiedName qualifiedName, UpdateSchemaRequest updateSchemaRequest) {
        final RequestContext requestContext = RequestContextManager.getContext();
        log.info("update request context: " + requestContext);

        this.accessController.checkPermission("", "", SchemaOperation.UPDATE);

        SchemaInfo current = storageServiceProxy.get(qualifiedName, config.isCacheEnabled());
        if (current == null) {
            throw new SchemaNotFoundException("Schema " + qualifiedName + " not exist, ignored update.");
        }

        current.getAudit().updateBy(updateSchemaRequest.getOwner());

        final SchemaRecordInfo updateRecord = new SchemaRecordInfo();
        updateRecord.setSchema(qualifiedName.schemaFullName());
        updateRecord.setSchemaId(current.getUniqueId());
        updateRecord.setType(current.getSchemaType());
        updateRecord.setIdl(updateSchemaRequest.getSchemaIdl());
        updateRecord.bindSubject(qualifiedName.subjectInfo());
        updateRecord.setVersion(current.getLastRecordVersion() + 1);

        final List<SchemaRecordInfo> updateRecords = new ArrayList<>(current.getDetails().getSchemaRecords());
        updateRecords.add(updateRecord);

        final SchemaInfo update = new SchemaInfo();
        update.getDetails().setSchemaRecords(updateRecords);

        if (current.getQualifiedName() != null) {
            update.setQualifiedName(current.getQualifiedName());
        }

        if (current.getMeta() != null) {
            update.setMeta(current.getMeta());
        }

        if (current.getStorage() != null) {
            update.setStorage(current.getStorage());
        }

        if (update.getExtras() != null) {
            update.setExtras(current.getExtras());
        }

        CommonUtil.validateCompatibility(update, current, current.getMeta().getCompatibility());

        if (config.isUploadEnabled()) {
            Dependency dependency = dependencyService.compile(update);
            update.setLastRecordDependency(dependency);
        }

        log.info("Updating schema info {}: {}", qualifiedName, update);
        storageServiceProxy.update(qualifiedName, update);
        return new UpdateSchemaResponse(updateRecord.getSchemaId(), updateRecord.getVersion());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SchemaDto delete(QualifiedName qualifiedName) {
        final RequestContext requestContext = RequestContextManager.getContext();
        log.info("delete request context: " + requestContext);

        this.accessController.checkPermission("", qualifiedName.getTenant(), SchemaOperation.DELETE);

        SchemaInfo current = storageServiceProxy.get(qualifiedName, config.isCacheEnabled());
        if (current == null) {
            throw new SchemaNotFoundException("Schema " + qualifiedName + " not exist, ignored update.");
        }

        log.info("delete schema {}", qualifiedName);
        storageServiceProxy.delete(qualifiedName);
        return storageUtil.convertToSchemaDto(current);
    }

    // TODO add get last record query

    /**
     * {@inheritDoc}
     */
    @Override
    public SchemaDto get(QualifiedName qualifiedName) {
        final RequestContext requestContext = RequestContextManager.getContext();
        log.info("register get request context: " + requestContext);

        CommonUtil.validateName(qualifiedName);

        this.accessController.checkPermission("", qualifiedName.getTenant(), SchemaOperation.GET);

        SchemaInfo schemaInfo = storageServiceProxy.get(qualifiedName, config.isCacheEnabled());
        if (schemaInfo == null) {
            throw new SchemaNotFoundException(qualifiedName);
        }

        log.info("get schema {}", qualifiedName);
        return storageUtil.convertToSchemaDto(schemaInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SchemaRecordDto getBySubject(QualifiedName qualifiedName) {
        final RequestContext requestContext = RequestContextManager.getContext();
        log.info("register get request context: " + requestContext);

//        CommonUtil.validateName(qualifiedName);
        this.accessController.checkPermission("", qualifiedName.getSubject(), SchemaOperation.GET);

        SchemaRecordInfo recordInfo = storageServiceProxy.getBySubject(qualifiedName, config.isCacheEnabled());
        if (recordInfo == null) {
            throw new SchemaException("Subject: " + qualifiedName + " not exist");
        }

        log.info("get schema by subject: {}", qualifiedName.getSubject());
        return storageUtil.convertToSchemaRecordDto(recordInfo);
    }

    @Override
    public List<SchemaRecordDto> listBySubject(QualifiedName qualifiedName) {
        final RequestContext requestContext = RequestContextManager.getContext();
        log.info("register get request context: " + requestContext);

        //        CommonUtil.validateName(qualifiedName);
        this.accessController.checkPermission("", qualifiedName.getSubject(), SchemaOperation.GET);

        List<SchemaRecordInfo> recordInfos = storageServiceProxy.listBySubject(qualifiedName, config.isCacheEnabled());
        if (recordInfos == null) {
            throw new SchemaException("Subject: " + qualifiedName + " not exist");
        }

        log.info("list schema by subject: {}", qualifiedName.getSubject());
        return recordInfos.stream().map(storageUtil::convertToSchemaRecordDto).collect(Collectors.toList());
    }

    private void checkSchemaExist(final QualifiedName qualifiedName) {
        if (storageServiceProxy.get(qualifiedName, config.isCacheEnabled()) != null) {
            throw new SchemaExistException(qualifiedName);
        }
    }

    private void checkSchemaValid(final SchemaDto schemaDto) {
        CommonUtil.validateName(schemaDto.getQualifiedName());

        // TODO: check and set namespace from idl
        if (Strings.isNullOrEmpty(schemaDto.getMeta().getNamespace())) {
            throw new SchemaCompatibilityException("Schema namespace is null, please check your config.");
        }

        if (schemaDto.getDetails().getSchemaRecords().size() > 1) {
            throw new SchemaCompatibilityException("Can not register schema with multi records.");
        }
    }
}
