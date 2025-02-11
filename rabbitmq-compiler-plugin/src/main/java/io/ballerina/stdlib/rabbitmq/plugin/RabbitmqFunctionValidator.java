/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.rabbitmq.plugin;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ErrorTypeSymbol;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDefinitionSymbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.ParameterNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.RequiredParameterNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.rabbitmq.plugin.PluginConstants.CompilationErrors;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.ballerina.stdlib.rabbitmq.plugin.PluginUtils.getMethodSymbol;
import static io.ballerina.stdlib.rabbitmq.plugin.PluginUtils.validateModuleId;

/**
 * RabbitMQ remote function validator.
 */
public class RabbitmqFunctionValidator {

    private final SyntaxNodeAnalysisContext context;
    private final ServiceDeclarationNode serviceDeclarationNode;
    FunctionDefinitionNode onMessage;
    FunctionDefinitionNode onRequest;
    FunctionDefinitionNode onError;

    public RabbitmqFunctionValidator(SyntaxNodeAnalysisContext context, FunctionDefinitionNode onMessage,
                                     FunctionDefinitionNode onRequest, FunctionDefinitionNode onError) {
        this.context = context;
        this.serviceDeclarationNode = (ServiceDeclarationNode) context.node();
        this.onMessage = onMessage;
        this.onRequest = onRequest;
        this.onError = onError;
    }

    public void validate() {
        validateMandatoryFunction();
        if (Objects.nonNull(onMessage)) {
            validateOnMessage();
        }
        if (Objects.nonNull(onRequest)) {
            validateOnRequest();
        }
        if (Objects.nonNull(onError)) {
            validateOnError();
        }
    }

    private void validateMandatoryFunction() {
        if (Objects.isNull(onMessage) && Objects.isNull(onRequest)) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.NO_ON_MESSAGE_OR_ON_REQUEST,
                    DiagnosticSeverity.ERROR, serviceDeclarationNode.location()));
        } else if (!Objects.isNull(onMessage) && !Objects.isNull(onRequest)) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(CompilationErrors.ON_MESSAGE_OR_ON_REQUEST,
                    DiagnosticSeverity.ERROR, serviceDeclarationNode.location()));
        }
    }

    private void validateOnMessage() {
        if (!PluginUtils.isRemoteFunction(context, onMessage)) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.FUNCTION_SHOULD_BE_REMOTE,
                    DiagnosticSeverity.ERROR, onMessage.functionSignature().location()));
        }
        SeparatedNodeList<ParameterNode> parameters = onMessage.functionSignature().parameters();
        validateFunctionParameters(parameters, onMessage);
        validateReturnTypeErrorOrNil(onMessage);
    }

    private void validateOnRequest() {
        if (!PluginUtils.isRemoteFunction(context, onRequest)) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.FUNCTION_SHOULD_BE_REMOTE,
                    DiagnosticSeverity.ERROR, onRequest.functionSignature().location()));
        }
        SeparatedNodeList<ParameterNode> parameters = onRequest.functionSignature().parameters();
        validateFunctionParameters(parameters, onRequest);
        validateOnRequestReturnType(onRequest);
    }

    private void validateOnError() {
        if (!PluginUtils.isRemoteFunction(context, onError)) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.FUNCTION_SHOULD_BE_REMOTE,
                    DiagnosticSeverity.ERROR, onError.functionSignature().location()));
        }
        SeparatedNodeList<ParameterNode> parameters = onError.functionSignature().parameters();
        validateOnErrorFunctionParameters(parameters, onError);
        validateReturnTypeErrorOrNil(onError);
    }

    private void validateFunctionParameters(SeparatedNodeList<ParameterNode> parameters,
                                            FunctionDefinitionNode functionDefinitionNode) {
        if (parameters.size() == 1) {
            validateFirstParam(parameters.get(0));
        } else if (parameters.size() == 2) {
            validateFirstParam(parameters.get(0));
            validateSecondParam(parameters.get(1));
        }
        if (parameters.size() < 1) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(CompilationErrors.MUST_HAVE_MESSAGE,
                    DiagnosticSeverity.ERROR, functionDefinitionNode.functionSignature().location()));
        }
        if (parameters.size() > 2) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(CompilationErrors.ONLY_PARAMS_ALLOWED,
                    DiagnosticSeverity.ERROR, functionDefinitionNode.functionSignature().location()));
        }
    }

    private void validateOnErrorFunctionParameters(SeparatedNodeList<ParameterNode> parameters,
                                                   FunctionDefinitionNode functionDefinitionNode) {
        if (parameters.size() > 1) {
            validateFirstParam(parameters.get(0));
            validateErrorParam(parameters.get(1));
        }
        if (parameters.size() < 2) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(CompilationErrors.MUST_HAVE_MESSAGE_AND_ERROR,
                    DiagnosticSeverity.ERROR, functionDefinitionNode.functionSignature().location()));
        }
        if (parameters.size() > 2) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(CompilationErrors.ONLY_PARAMS_ALLOWED_ON_ERROR,
                    DiagnosticSeverity.ERROR, functionDefinitionNode.functionSignature().location()));
        }
    }

    private void validateFirstParam(ParameterNode parameterNode) {
        RequiredParameterNode requiredParameterNode = (RequiredParameterNode) parameterNode;
        Node parameterTypeNode = requiredParameterNode.typeName();
        SemanticModel semanticModel = context.semanticModel();
        Optional<Symbol> paramSymbol = semanticModel.symbol(parameterTypeNode);
        if (paramSymbol.isPresent()) {
            Optional<ModuleSymbol> moduleSymbol = paramSymbol.get().getModule();
            if (moduleSymbol.isPresent()) {
                String paramName = paramSymbol.get().getName().isPresent() ?
                        paramSymbol.get().getName().get() : "";
                if (!validateModuleId(moduleSymbol.get())) {
                    context.reportDiagnostic(PluginUtils.getDiagnostic(
                            CompilationErrors.INVALID_FUNCTION_PARAM_MESSAGE,
                            DiagnosticSeverity.ERROR, requiredParameterNode.location()));
                } else {
                    if (!paramName.equals(PluginConstants.MESSAGE)) {
                        String typeName = getTypeDefinitionNameForMessage();
                        if (!paramName.equals(typeName)) {
                            context.reportDiagnostic(PluginUtils.getDiagnostic(
                                    CompilationErrors.INVALID_FUNCTION_PARAM_MESSAGE,
                                    DiagnosticSeverity.ERROR, requiredParameterNode.location()));
                        }
                    }
                }
            } else {
                context.reportDiagnostic(PluginUtils.getDiagnostic(
                        CompilationErrors.INVALID_FUNCTION_PARAM_MESSAGE,
                        DiagnosticSeverity.ERROR, requiredParameterNode.location()));
            }
        }
    }

    private String getTypeDefinitionNameForMessage() {
        SemanticModel semanticModel = context.semanticModel();
        List<Symbol> moduleSymbols = semanticModel.moduleSymbols();
        for (Symbol symbol: moduleSymbols) {
            if (symbol.kind() == SymbolKind.TYPE_DEFINITION) {
                TypeDefinitionSymbol definitionSymbol = (TypeDefinitionSymbol) symbol;
                if (definitionSymbol.typeDescriptor().typeKind() == TypeDescKind.RECORD) {
                    Map<String, RecordFieldSymbol> record =
                            ((RecordTypeSymbol) definitionSymbol.typeDescriptor()).fieldDescriptors();
                    if (record.size() == 5 &&
                            record.containsKey(PluginConstants.CONTENT_FIELD) &&
                            record.containsKey(PluginConstants.ROUTING_KEY_FIELD) &&
                            record.containsKey(PluginConstants.EXCHANGE_FIELD) &&
                            record.containsKey(PluginConstants.DELIVERY_TAG_FIELD) &&
                            record.containsKey(PluginConstants.PROPS_FIELD)) {
                        return definitionSymbol.getName().get();
                    }
                }
            }
        }
        return null;
    }

    private void validateSecondParam(ParameterNode parameterNode) {
        RequiredParameterNode requiredParameterNode = (RequiredParameterNode) parameterNode;
        Node parameterTypeNode = requiredParameterNode.typeName();
        if (parameterTypeNode.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            QualifiedNameReferenceNode callerNode = (QualifiedNameReferenceNode) parameterTypeNode;
            SemanticModel semanticModel = context.semanticModel();
            Optional<Symbol> paramSymbol = semanticModel.symbol(callerNode);
            if (paramSymbol.isPresent()) {
                Optional<ModuleSymbol> moduleSymbol = paramSymbol.get().getModule();
                if (moduleSymbol.isPresent()) {
                    String paramName = paramSymbol.get().getName().isPresent() ?
                            paramSymbol.get().getName().get() : "";
                    if (!validateModuleId(moduleSymbol.get()) ||
                            !paramName.equals(PluginConstants.CALLER)) {
                        context.reportDiagnostic(PluginUtils.getDiagnostic(
                                CompilationErrors.INVALID_FUNCTION_PARAM_CALLER,
                                DiagnosticSeverity.ERROR, requiredParameterNode.location()));
                    }
                }
            }
        } else {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.INVALID_FUNCTION_PARAM_CALLER,
                    DiagnosticSeverity.ERROR, requiredParameterNode.location()));
        }
    }

    private void validateReturnTypeErrorOrNil(FunctionDefinitionNode functionDefinitionNode) {
        MethodSymbol methodSymbol = getMethodSymbol(context, functionDefinitionNode);
        if (methodSymbol != null) {
            Optional<TypeSymbol> returnTypeDesc = methodSymbol.typeDescriptor().returnTypeDescriptor();
            if (returnTypeDesc.isPresent()) {
                if (returnTypeDesc.get().typeKind() == TypeDescKind.UNION) {
                    List<TypeSymbol> returnTypeMembers =
                            ((UnionTypeSymbol) returnTypeDesc.get()).memberTypeDescriptors();
                    for (TypeSymbol returnType : returnTypeMembers) {
                        if (returnType.typeKind() != TypeDescKind.NIL) {
                            if (returnType.typeKind() == TypeDescKind.ERROR) {
                                if (!returnType.signature().equals(PluginConstants.ERROR) &&
                                        !validateModuleId(returnType.getModule().get())) {
                                    context.reportDiagnostic(PluginUtils.getDiagnostic(
                                            CompilationErrors.INVALID_RETURN_TYPE_ERROR_OR_NIL,
                                            DiagnosticSeverity.ERROR, functionDefinitionNode.location()));
                                }
                            } else {
                                context.reportDiagnostic(PluginUtils.getDiagnostic(
                                        CompilationErrors.INVALID_RETURN_TYPE_ERROR_OR_NIL,
                                        DiagnosticSeverity.ERROR, functionDefinitionNode.location()));
                            }
                        }
                    }
                } else if (returnTypeDesc.get().typeKind() != TypeDescKind.NIL) {
                    context.reportDiagnostic(PluginUtils.getDiagnostic(
                            CompilationErrors.INVALID_RETURN_TYPE_ERROR_OR_NIL,
                            DiagnosticSeverity.ERROR, functionDefinitionNode.location()));
                }
            }
        }
    }

    private void validateOnRequestReturnType(FunctionDefinitionNode functionDefinitionNode) {
        MethodSymbol methodSymbol = getMethodSymbol(context, functionDefinitionNode);
        if (methodSymbol != null) {
            Optional<TypeSymbol> returnTypeDesc = methodSymbol.typeDescriptor().returnTypeDescriptor();
            if (returnTypeDesc.isPresent()) {
                if (returnTypeDesc.get().typeKind() == TypeDescKind.UNION) {
                    List<TypeSymbol> returnTypeMembers =
                            ((UnionTypeSymbol) returnTypeDesc.get()).memberTypeDescriptors();
                    for (TypeSymbol returnType : returnTypeMembers) {
                        if (returnType.typeKind() != TypeDescKind.NIL) {
                            if (returnType.typeKind() == TypeDescKind.ERROR) {
                                if (!returnType.signature().equals(PluginConstants.ERROR) &&
                                        !validateModuleId(returnType.getModule().get())) {
                                    context.reportDiagnostic(PluginUtils.getDiagnostic(
                                            CompilationErrors.INVALID_RETURN_TYPE_ERROR_OR_NIL,
                                            DiagnosticSeverity.ERROR, functionDefinitionNode.location()));
                                }
                            } else {
                                validateAnyDataReturnType(returnTypeDesc.get().signature(), functionDefinitionNode);
                            }
                        }
                    }
                } else if (returnTypeDesc.get().typeKind() != TypeDescKind.NIL) {
                    validateAnyDataReturnType(returnTypeDesc.get().signature(), functionDefinitionNode);
                }
            }
        }
    }

    private void validateAnyDataReturnType(String returnType, FunctionDefinitionNode functionDefinitionNode) {
        if (!Arrays.asList(PluginConstants.ANY_DATA_RETURN_VALUES).contains(returnType)) {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.INVALID_RETURN_TYPE_ANY_DATA,
                    DiagnosticSeverity.ERROR, functionDefinitionNode.location()));
        }
    }

    private void validateErrorParam(ParameterNode parameterNode) {
        RequiredParameterNode requiredParameterNode = (RequiredParameterNode) parameterNode;
        Node parameterTypeNode = requiredParameterNode.typeName();
        if (parameterTypeNode.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            QualifiedNameReferenceNode errorNode = (QualifiedNameReferenceNode) parameterTypeNode;
            SemanticModel semanticModel = context.semanticModel();
            Optional<Symbol> paramSymbol = semanticModel.symbol(errorNode);
            if (paramSymbol.isPresent()) {
                Optional<ModuleSymbol> moduleSymbol = paramSymbol.get().getModule();
                if (moduleSymbol.isPresent()) {
                    if (!validateModuleId(moduleSymbol.get()) ||
                            !(paramSymbol.get() instanceof ErrorTypeSymbol)) {
                        context.reportDiagnostic(PluginUtils.getDiagnostic(
                                CompilationErrors.INVALID_FUNCTION_PARAM_ERROR,
                                DiagnosticSeverity.ERROR, requiredParameterNode.location()));
                    }
                }
            }
        } else {
            context.reportDiagnostic(PluginUtils.getDiagnostic(
                    CompilationErrors.INVALID_FUNCTION_PARAM_ERROR,
                    DiagnosticSeverity.ERROR, requiredParameterNode.location()));
        }
    }
}
