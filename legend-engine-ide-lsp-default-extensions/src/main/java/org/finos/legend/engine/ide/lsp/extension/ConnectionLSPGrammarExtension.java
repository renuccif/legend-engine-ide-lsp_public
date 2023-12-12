// Copyright 2023 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.ide.lsp.extension;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.engine.ide.lsp.extension.execution.LegendExecutionResult;
import org.finos.legend.engine.ide.lsp.extension.execution.LegendExecutionResult.Type;
import org.finos.legend.engine.ide.lsp.extension.state.SectionState;
import org.finos.legend.engine.language.pure.grammar.from.connection.ConnectionParser;
import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtensionLoader;
import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtensions;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.PackageableElement;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.PackageableConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.RelationalDatabaseConnection;

import java.util.Collections;
import java.util.Map;

/**
 * Extension for the Connection grammar.
 */
public class ConnectionLSPGrammarExtension extends AbstractLegacyParserLSPGrammarExtension
{
    private static final String GENERATE_DB_COMMAND_ID = "legend.service.generateDatabase";
    private static final String GENERATE_DB_COMMAND_TITLE = "Generate database";

    private final ListIterable<String> keywords;

    public ConnectionLSPGrammarExtension()
    {
        super(ConnectionParser.newInstance(PureGrammarParserExtensions.fromAvailableExtensions()));
        this.keywords = findKeywords();
    }

    @Override
    public Iterable<? extends String> getKeywords()
    {
        return this.keywords;
    }

    @Override
    protected void collectCommands(SectionState sectionState, PackageableElement element, CommandConsumer consumer)
    {
        super.collectCommands(sectionState, element, consumer);
        if (isEngineServerConfigured() && (element instanceof PackageableConnection))
        {
            PackageableConnection packageableConn = (PackageableConnection) element;
            if (packageableConn.connectionValue instanceof RelationalDatabaseConnection)
            {
                consumer.accept(GENERATE_DB_COMMAND_ID, GENERATE_DB_COMMAND_TITLE, packageableConn.sourceInformation);
            }
        }
    }

    @Override
    public Iterable<? extends LegendExecutionResult> execute(SectionState section, String entityPath, String commandId, Map<String, String> executableArgs)
    {
        return GENERATE_DB_COMMAND_ID.equals(commandId) ?
               generateDBFromConnection(section, entityPath) :
               super.execute(section, entityPath, commandId, executableArgs);
    }

    private Iterable<? extends LegendExecutionResult> generateDBFromConnection(SectionState section, String entityPath)
    {
        if (!isEngineServerConfigured())
        {
            return Collections.singletonList(LegendExecutionResult.newResult(entityPath, Type.ERROR, "Engine server is not configured"));
        }

        PackageableElement element = getParseResult(section).getElement(entityPath);
        if (!(element instanceof PackageableConnection))
        {
            return Collections.singletonList(LegendExecutionResult.newResult(entityPath, Type.ERROR, "Unable to find connection " + entityPath));
        }

        PackageableConnection packageableConn = (PackageableConnection) element;
        if (!(packageableConn.connectionValue instanceof RelationalDatabaseConnection))
        {
            return Collections.singletonList(LegendExecutionResult.newResult(entityPath, Type.ERROR, "Not a " + RelationalDatabaseConnection.class.getSimpleName() + ": " + entityPath));
        }

        MutableMap<String, Object> input = buildInput(packageableConn);
        PureModelContextData result = postEngineServer("/pure/v1/utilities/database/schemaExploration", input, PureModelContextData.class);
        String code = toGrammar(result);
        return Collections.singletonList(LegendExecutionResult.newResult(entityPath, Type.SUCCESS, code));
    }

    private MutableMap<String, Object> buildInput(PackageableConnection packageableConn)
    {
        MutableMap<String, String> pattern = Maps.mutable.with(
                "catalog", "%",
                "schemaPattern", "%",
                "tablePattern", "%"
        );
        MutableMap<String, Object> config = Maps.mutable.with(
                "enrichTables", true,
                "enrichPrimaryKeys", true,
                "enrichColumns", true,
                "patterns", Lists.fixedSize.with(pattern)
        );
        MutableMap<String, String> targetDB = Maps.mutable.with(
                "name", packageableConn.name + "Database",
                "package", packageableConn._package
        );
        return Maps.mutable.with(
                "config", config,
                "connection", packageableConn.connectionValue,
                "targetDatabase", targetDB);
    }

    private static ListIterable<String> findKeywords()
    {
        MutableSet<String> keywords = Sets.mutable.empty();
        PureGrammarParserExtensionLoader.extensions().forEach(ext -> ext.getExtraConnectionParsers().forEach(p -> keywords.add(p.getConnectionTypeName())));
        return Lists.immutable.withAll(keywords);
    }
}
