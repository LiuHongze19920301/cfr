package org.benf.cfr.reader.bytecode.analysis.parse.expression;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.entities.ConstantPool;
import org.benf.cfr.reader.entities.ConstantPoolEntryClass;
import org.benf.cfr.reader.entities.ConstantPoolEntryMethodRef;
import org.benf.cfr.reader.entities.ConstantPoolEntryNameAndType;
import org.benf.cfr.reader.util.ListFactory;

import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 16/03/2012
 * Time: 17:26
 * To change this template use File | Settings | File Templates.
 */
public class ConstructorInvokation implements Expression {
    private final ConstantPoolEntryMethodRef function;
    private final ConstantPoolEntryClass type;
    private final List<Expression> args;
    private final ConstantPool cp;

    public ConstructorInvokation(ConstantPool cp, ConstantPoolEntryMethodRef function, ConstantPoolEntryClass type, List<Expression> args) {
        this.function = function;
        this.type = type;
        this.args = args;
        this.cp = cp;
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public Expression replaceSingleUsageLValues(LValueCollector lValueCollector, SSAIdentifiers ssaIdentifiers) {
        for (int x = 0; x < args.size(); ++x) {
            args.set(x, args.get(x).replaceSingleUsageLValues(lValueCollector, ssaIdentifiers));
        }
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("new " + cp.getUTF8Entry(type.getNameIndex()).getValue() + ".");
        ConstantPoolEntryNameAndType nameAndType = cp.getNameAndTypeEntry(function.getNameAndTypeIndex());
        sb.append(nameAndType.getName(cp).getValue());
        sb.append("(");
        boolean first = true;
        for (Expression arg : args) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(arg.toString());
        }
        sb.append(")");
        return sb.toString();
    }

}