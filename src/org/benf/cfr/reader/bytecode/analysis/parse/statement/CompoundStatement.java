package org.benf.cfr.reader.bytecode.analysis.parse.statement;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.Statement;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.util.ConfusedCFRException;
import org.benf.cfr.reader.util.ListFactory;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 15/03/2012
 */
public class CompoundStatement extends AbstractStatement {
    private List<Statement> statements;

    public CompoundStatement(Statement... statements) {
        this.statements = ListFactory.newList(statements);
    }

    @Override
    public void dump(Dumper dumper) {
        dumper.print("{\n");
        for (Statement statement : statements) {
            statement.dump(dumper);
        }
        dumper.print("}\n");
    }

    @Override
    public void getLValueEquivalences(LValueCollector lValueCollector) {
        throw new ConfusedCFRException("Should not be using compound statements here");
    }

    @Override
    public LValue getCreatedLValue() {
        throw new ConfusedCFRException("Should not be using compound statements here");
    }

    @Override
    public Expression getRValue() {
        throw new ConfusedCFRException("Should not be using compound statements here");
    }

    @Override
    public void replaceSingleUsageLValues(LValueCollector lValueCollector, SSAIdentifiers ssaIdentifiers) {
        throw new ConfusedCFRException("Should not be using compound statements here");
    }

    @Override
    public boolean isCompound() {
        return true;
    }

    @Override
    public List<Statement> getCompoundParts() {
        return statements;
    }
}