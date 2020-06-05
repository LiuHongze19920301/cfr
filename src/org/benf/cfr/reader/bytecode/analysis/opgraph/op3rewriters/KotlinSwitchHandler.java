package org.benf.cfr.reader.bytecode.analysis.opgraph.op3rewriters;

import org.benf.cfr.reader.bytecode.BytecodeMeta;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.Statement;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.*;
import org.benf.cfr.reader.bytecode.analysis.parse.literal.TypedLiteral;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.statement.*;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.parse.wildcard.WildcardMatch;
import org.benf.cfr.reader.bytecode.analysis.types.RawJavaType;
import org.benf.cfr.reader.bytecode.analysis.types.TypeConstants;
import org.benf.cfr.reader.bytecode.analysis.types.discovery.InferredJavaType;
import org.benf.cfr.reader.bytecode.opcode.DecodedSwitch;
import org.benf.cfr.reader.bytecode.opcode.DecodedSwitchEntry;
import org.benf.cfr.reader.util.collections.Functional;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.collections.MapFactory;
import org.benf.cfr.reader.util.collections.SetFactory;
import org.benf.cfr.reader.util.functors.Predicate;
import org.benf.cfr.reader.util.functors.UnaryFunction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KotlinSwitchHandler {
    /*
     * Rather than the two switches in a row generated by java string switch (thanks, project COIN ;),
     * Kotlin generates
     *
     * switch (str.hash()) {
     *  case HASH1:
     *    if (str.equals("aa")) goto IMPL1;
     *    if (str.equals("bb")) goto IMPL2;
     *    goto default
     *  case HASH2:
     *    if (str.equals("cc")) goto IMPL3;
     *    goto default:
     *  IMPL1:
     *    // return/branch to after default
     *  IMPL2:
     *    // return/branch to after default
     *  IMPL3:
     *    // return/branch to after default
     *  default:
     *    BLAH
     *    // return/fall through.
     * }
     *
     * This is pretty nice, but a bitch to consider as having ever been java.  Switch re-ordering
     * rules means that a simple topsort (with rules) will ignore it, and we don't sort at this point
     * anyway......
     *
     * Instead, try to spot this pattern EXPLICITLY, and split it up into two switch statements, thus
     * rebuilding COIN code!
     */
    public static List<Op03SimpleStatement> extractStringSwitches(List<Op03SimpleStatement> in, BytecodeMeta bytecodeMeta) {
        List<Op03SimpleStatement> switchStatements = Functional.filter(in, new TypeFilter<RawSwitchStatement>(RawSwitchStatement.class));
        boolean action = false;
        for (Op03SimpleStatement swatch : switchStatements) {
            action |= extractStringSwitch(swatch, in, bytecodeMeta);
        }
        if (!action) return in;
        return Cleaner.sortAndRenumber(in);
    }

    // Everything except the default action should have a set of
    //   if (str.equals("aa")) goto IMPL1;
    // Note that we are dealing with RAW switches here, so have to decode default information manually.
    private static boolean extractStringSwitch(final Op03SimpleStatement swatch, List<Op03SimpleStatement> in, BytecodeMeta bytecodeMeta) {
        RawSwitchStatement rawSwitchStatement = (RawSwitchStatement)swatch.getStatement();
        Expression switchOn = rawSwitchStatement.getSwitchOn();

        WildcardMatch wcm = new WildcardMatch();
        WildcardMatch.ExpressionWildcard testObj = wcm.getExpressionWildCard("obj");
        WildcardMatch.MemberFunctionInvokationWildcard test = wcm.getMemberFunction("test", "hashCode", testObj);
        if (!test.equals(switchOn)) return false;

        Expression obj = testObj.getMatch();

        /*
         * If we've failed to spot an alias, due to some awkward dupping, or due to deliberate action, we
         * might be switching on a different thing than we're comparing(!).
         *
         * Go back and gather immediate aliases.
         */
        Set<Expression> aliases = SetFactory.newSet();
        aliases.add(obj);
        if (swatch.getSources().size() == 1) {
            Op03SimpleStatement backptr = swatch;
            do {
                backptr = backptr.getSources().get(0);
                Statement backTest = backptr.getStatement();
                if (backTest instanceof Nop) {
                    // continue
                } else if (backTest instanceof AssignmentSimple) {
                    AssignmentSimple backAss = (AssignmentSimple)backTest;
                    Expression lValue = new LValueExpression(backAss.getCreatedLValue());
                    Expression rValue = backAss.getRValue();
                    if (aliases.contains(lValue)) {
                        aliases.add(rValue);
                    } else if (aliases.contains(rValue)) {
                        aliases.add(lValue);
                    }
                    break;
                } else {
                    break;
                }
            } while (backptr.getSources().size() == 1);
        }
        Expression matchObj = new WildcardMatch.AnyOneOfExpression(aliases);

        DecodedSwitch switchData = rawSwitchStatement.getSwitchData();
        List<DecodedSwitchEntry> jumpTargets = switchData.getJumpTargets();
        List<Op03SimpleStatement> targets = swatch.getTargets();
        if (jumpTargets.size() != targets.size()) return false;
        int defaultBranchIdx = -1;
        for (int x=0;x<jumpTargets.size();++x) {
            if (jumpTargets.get(x).hasDefault()) {
                defaultBranchIdx = x;
                break;
            }
        }
        if (defaultBranchIdx == -1) return false;
        Op03SimpleStatement defaultTarget = targets.get(defaultBranchIdx);
        Op03SimpleStatement afterDefault = Misc.followNopGotoChain(defaultTarget, false, true);

        WildcardMatch.MemberFunctionInvokationWildcard eqFn = wcm.getMemberFunction("equals", "equals", matchObj,
                new CastExpression(new InferredJavaType(TypeConstants.OBJECT, InferredJavaType.Source.UNKNOWN),
                        wcm.getExpressionWildCard("value"))
        );
        IfStatement testIf = new IfStatement(new ComparisonOperation(eqFn, Literal.FALSE, CompOp.EQ));
        IfStatement testNotIf = new IfStatement(new ComparisonOperation(eqFn, Literal.FALSE, CompOp.NE));
        final Set<Op03SimpleStatement> reTargetSet = SetFactory.newIdentitySet();
        final Map<Op03SimpleStatement, DistinctSwitchTarget> reTargets = MapFactory.newIdentityLazyMap(new UnaryFunction<Op03SimpleStatement, DistinctSwitchTarget>() {
            @Override
            public DistinctSwitchTarget invoke(Op03SimpleStatement arg) {
                reTargetSet.add(arg);
                return new DistinctSwitchTarget(reTargetSet.size());
            }
        });
        List<List<OriginalSwitchLookupInfo>> matchesFound = ListFactory.newList();
        List<Pair<Op03SimpleStatement, Op03SimpleStatement>> transitiveDefaultSources = ListFactory.newList();
        for (int x=0;x<jumpTargets.size();++x) {
            Op03SimpleStatement caseStart = targets.get(x);
            DecodedSwitchEntry switchEntry = jumpTargets.get(x);

            // If it's the default statement, I don't expect to find any string tests there.
            if (switchEntry.hasDefault()) {
                continue;
            }

            Op03SimpleStatement currentCaseLoc = caseStart;
            List<OriginalSwitchLookupInfo> found = ListFactory.newList();
            do {
                Op03SimpleStatement nextCaseLoc = null;

                Statement maybeIf = currentCaseLoc.getStatement();
                if (maybeIf.getClass() == GotoStatement.class) {
                    if (currentCaseLoc.getTargets().get(0) == defaultTarget) {
                        break;
                    } else {
                        return false;
                    }
                }
                wcm.reset();
                if (testIf.equals(maybeIf)) {
                    Expression value = wcm.getExpressionWildCard("value").getMatch();
                    if (value instanceof Literal) {
                        TypedLiteral literal = ((Literal) value).getValue();
                        if (literal.getType() == TypedLiteral.LiteralType.String) {
                            List<Op03SimpleStatement> nextStatements = currentCaseLoc.getTargets();
                            Op03SimpleStatement nextTest = nextStatements.get(1);
                            Op03SimpleStatement stringMatchJump = nextStatements.get(0);
                            if (stringMatchJump.getStatement().getClass() == GotoStatement.class) {
                                Op03SimpleStatement stringMatch = stringMatchJump.getTargets().get(0);
                                OriginalSwitchLookupInfo match = new OriginalSwitchLookupInfo(currentCaseLoc, stringMatchJump, literal, stringMatch);
                                found.add(match);
                                reTargets.get(stringMatch).add(match);
                                nextCaseLoc = nextTest;
                                if (nextCaseLoc == defaultTarget) {
                                    transitiveDefaultSources.add(Pair.make(currentCaseLoc, defaultTarget));
                                }
                            }
                        }
                    }
                } else {
                    /*
                     * If we get here, it's possible that instead of
                     * if (x.equals("bar")) goto tgt
                     * goto default
                     *
                     * we've got
                     *
                     * if (!x.equals("bar")) goto default
                     * goto tgt.
                     *
                     * This can only happen once.
                     */
                    if (testNotIf.equals(maybeIf)) {
                        Expression value = wcm.getExpressionWildCard("value").getMatch();
                        if (value instanceof Literal) {
                            TypedLiteral literal = ((Literal) value).getValue();
                            if (literal.getType() == TypedLiteral.LiteralType.String) {
                                List<Op03SimpleStatement> nextStatements = currentCaseLoc.getTargets();
                                Op03SimpleStatement nextTest = nextStatements.get(0);
                                Op03SimpleStatement stringMatch = nextStatements.get(1);
                                OriginalSwitchLookupInfo match = new OriginalSwitchLookupInfo(currentCaseLoc, null, literal, stringMatch);
                                found.add(match);
                                // We need to keep track of defaults as defaults need to be changed to point to nop
                                // after the ORIGINAL block.
                                reTargets.get(stringMatch).add(match);
                                if (nextTest == defaultTarget) {
                                    transitiveDefaultSources.add(Pair.make(currentCaseLoc, defaultTarget));
                                    nextCaseLoc = nextTest;
                                } else if (nextTest.getStatement().getClass() == GotoStatement.class) {
                                    Op03SimpleStatement nextTarget = Misc.followNopGotoChainUntil(nextTest, defaultTarget, true, false);
                                    // It's only valid to follow a chain if it ends up in the default.
                                    if (nextTarget == defaultTarget || nextTarget == afterDefault) {
                                        transitiveDefaultSources.add(Pair.make(nextTest, nextTest.getTargets().get(0)));
                                        nextCaseLoc = nextTarget;
                                    } else {
                                        nextCaseLoc = nextTest;
                                    }
                                }
                            }
                        }
                    }
                }

                if (nextCaseLoc == defaultTarget || nextCaseLoc == afterDefault) {
                    break;
                }
                if (nextCaseLoc == null) {
                    return false;
                }
                currentCaseLoc = nextCaseLoc;
            } while (true);
            matchesFound.add(found);
        }

        /*
         * Check we haven't actually encountered a java switch that's been explicitly stated.
         * (in a way we'd successfully recover it later).
         */
        LValue foundValue = null;
        for (Op03SimpleStatement retarget : reTargetSet) {
            // Find one of these that is an assign, and ca
            Statement reStatement = retarget.getStatement();
            if (reStatement instanceof AssignmentSimple) {
                foundValue = reStatement.getCreatedLValue();
                break;
            }
        }
        if (foundValue != null) {
            Op03SimpleStatement defaultTran = Misc.followNopGotoChain(defaultTarget, true, false);
            Statement defaultStm = defaultTran.getStatement();
            if (defaultStm instanceof RawSwitchStatement) {
                Expression switchOn2 = ((RawSwitchStatement) defaultStm).getSwitchOn();
                if (switchOn2 != null && switchOn2.equals(new LValueExpression(foundValue))) {
                    return false;
                }
            }
        }

        List<Op03SimpleStatement> secondSwitchTargets = ListFactory.newList(reTargets.keySet());
        Collections.sort(secondSwitchTargets, new CompareByIndex());
        List<Op03SimpleStatement> fwds = Functional.filter(secondSwitchTargets, new Predicate<Op03SimpleStatement>() {
            @Override
            public boolean test(Op03SimpleStatement in) {
                return in.getIndex().isBackJumpTo(swatch);
            }
        });
        if (fwds.isEmpty()) {
            // No forward targets?  Have to introduce a synthetic one?!
            return false;
        }
        Op03SimpleStatement firstCase2 = fwds.get(0);

        /*
         * FORM2
         * We know by this point we're ok to rebuild.  But if we encountered the second type of switch,
         * we have structures like this
         *  switch (str.hash()) {
         *  case HASH1:
         *    if (str.equals("aa")) goto IMPL1
         *    if (str.equals("bb")) goto IMPL2
         *    goto default
         *  case HASH2:
         *    if (str.equals("cc")) goto IMPL1
         *    goto default:
         *  IMPL1:
         *    // return/branch to after default
         *  IMPL2:
         *    // return/branch to after default
         *  default:
         *    BLAH
         *    // return/fall through.
         * }
         *
         * This is actually really nice, and as close as you can get to the intention.
         * BUT it's a bitch to resugar into a nice switch statement!
         * So we convert it into FORM1, which will further get converted into FORM0.
         */
        for (List<OriginalSwitchLookupInfo> matches : matchesFound) {
            for (OriginalSwitchLookupInfo match : matches) {
                if (match.stringMatchJump == null) {
                    Op03SimpleStatement ifTest = match.ifTest;
                    IfStatement statement = (IfStatement)ifTest.getStatement();
                    statement.setCondition(statement.getCondition().getNegated());
                    // replace
                    // if (A) goto TGT
                    // x:
                    //
                    // with
                    // if (!A) goto x
                    // goto TGT
                    // x
                    Op03SimpleStatement stringTgt = ifTest.getTargets().get(1);
                    Op03SimpleStatement fallThrough = ifTest.getTargets().get(0);
                    Op03SimpleStatement newFallThrough = new Op03SimpleStatement(fallThrough.getBlockIdentifiers(), new GotoStatement(), ifTest.getIndex().justAfter());
                    in.add( newFallThrough);
                    stringTgt.replaceSource(ifTest, newFallThrough);
                    newFallThrough.addTarget(stringTgt);
                    newFallThrough.addSource(ifTest);
                    ifTest.getTargets().set(0, newFallThrough);
                    ifTest.getTargets().set(1, fallThrough);
                    match.stringMatchJump = newFallThrough;
                }
            }
        }

        /*
         * FORM1
         * If we've got as far as here, then we know that we can replace the top of our original switch statement with
         * a switch that sets a temp var, and snip the bottom from the original switch statement to replace it with
         * a switch that vectors directly to the choices.
         * (which sounds suspiciously like a project coin string switch, yay!)
         *
         *  switch (str.hash()) {
         *  case HASH1:
         *    if (!str.equals("aa")) goto x
         *    goto IMPL1;
         *    x: if (str.equals("bb")) goto y
         *    goto IMPL2;
         *    y:
         *    goto default
         *  case HASH2:
         *    if (!str.equals("cc")) goto z
         *    goto IMPL1;
         *    z:
         *    goto default:
         *  IMPL1:
         *    // return/branch to after default
         *  IMPL2:
         *    // return/branch to after default
         *  default:
         *    BLAH
         *    // return/fall through.
         * }
         *
         * --> FORM0.
         * tmp = -1;
         * switch (str.hash) {
         *  case HASH1:
         *    if (!str.equals("aa")) goto x:
         *    tmp = 1
         *    goto endswitch
         *    x: if (!str.equals("bb")) goto y:
         *    tmp = 2
         *    goto endswitch
         *    y :
         *    goto endswitch
         *  case HASH2:
         *    if (!str.equals("cc")) goto z;
         *    tmp = 1
         *    goto endswitch
         *    z:
         *    goto endswitch
         * }
         * endswitch:
         * switch (tmp) {
         *   case 1:
         *     // IMPL1
         *   case 2:
         *     // IMPL2
         *   default:
         *     // BLAH
         * }
         */
        LValue lValue = new LocalVariable("tmp", new InferredJavaType(RawJavaType.INT, InferredJavaType.Source.UNKNOWN));
        Expression lValueExpr = new LValueExpression(lValue);

        /*
         * Build a new switch entry for each of the remapped one.
         */
        List<DecodedSwitchEntry> switchTargets = ListFactory.newList();
        for (Op03SimpleStatement target : secondSwitchTargets) {
            DistinctSwitchTarget distinctSwitchTarget = reTargets.get(target);
            List<Integer> tmp2 = ListFactory.newList();
            tmp2.add(distinctSwitchTarget.idx);
            DecodedSwitchEntry entry = new DecodedSwitchEntry(tmp2,-1);
            switchTargets.add(entry);
            for (OriginalSwitchLookupInfo originalSwitchLookupInfo : distinctSwitchTarget.entries) {
                Op03SimpleStatement from = originalSwitchLookupInfo.stringMatchJump;
                target.removeSource(from);
                from.removeGotoTarget(target);
            }
        }
        /* Remove everything that was pointing at default
         * We'll link the start of the second switch instead.
         */
        for (Pair<Op03SimpleStatement, Op03SimpleStatement> defaultSourceAndImmediate : transitiveDefaultSources) {
            Op03SimpleStatement defaultSource = defaultSourceAndImmediate.getFirst();
            Op03SimpleStatement localTarget = defaultSourceAndImmediate.getSecond();
            localTarget.removeSource(defaultSource);
            defaultSource.removeGotoTarget(localTarget);
        }

        List<Integer> defaultSecondary = ListFactory.newList();
        defaultSecondary.add(null);
        switchTargets.add(new DecodedSwitchEntry(defaultSecondary, -1));
        DecodedSwitch info = new FakeSwitch(switchTargets);
        RawSwitchStatement secondarySwitch = new RawSwitchStatement(lValueExpr, info);
        Op03SimpleStatement secondarySwitchStm = new Op03SimpleStatement(firstCase2.getBlockIdentifiers(), secondarySwitch, firstCase2.getIndex().justBefore());
        /*
         * We need to remove the target from each of the discovered
         */
        for (Op03SimpleStatement target : secondSwitchTargets) {
            secondarySwitchStm.addTarget(target);
            target.addSource(secondarySwitchStm);
        }
        secondarySwitchStm.addTarget(defaultTarget);
        defaultTarget.addSource(secondarySwitchStm);
        in.add(secondarySwitchStm);

        // Place a nop at the end of the first switch.
        Op03SimpleStatement nopHolder = new Op03SimpleStatement(firstCase2.getBlockIdentifiers(), new Nop(), secondarySwitchStm.getIndex().justBefore());
        // Link all defaults to it.
        for (Pair<Op03SimpleStatement, Op03SimpleStatement> defaultSourceAndImmediate : transitiveDefaultSources) {
            Op03SimpleStatement defaultSource = defaultSourceAndImmediate.getFirst();
            defaultSource.addTarget(nopHolder);
            nopHolder.addSource(defaultSource);
        }
        for (Op03SimpleStatement target : secondSwitchTargets) {
            DistinctSwitchTarget distinctSwitchTarget = reTargets.get(target);
            for (OriginalSwitchLookupInfo originalSwitchLookupInfo : distinctSwitchTarget.entries) {
                Op03SimpleStatement from = originalSwitchLookupInfo.stringMatchJump;
                AssignmentSimple assign = new AssignmentSimple(lValue, new Literal(TypedLiteral.getInt(distinctSwitchTarget.idx)));
                from.replaceStatement(assign);
                Op03SimpleStatement newJmp = new Op03SimpleStatement(from.getBlockIdentifiers(), new GotoStatement(), from.getIndex().justAfter());
                from.addTarget(newJmp);
                newJmp.addSource(from);
                newJmp.addTarget(nopHolder);
                in.add(newJmp);
                nopHolder.addSource(newJmp);
            }
        }

        in.add(nopHolder);
        nopHolder.addTarget(secondarySwitchStm);
        secondarySwitchStm.addSource(nopHolder);
        defaultTarget.removeSource(swatch);
        swatch.replaceTarget(defaultTarget, nopHolder);
        nopHolder.addSource(swatch);

        /*
         * And initialise the intermediate var to -1 at the start.
         */
        Op03SimpleStatement init = new Op03SimpleStatement(swatch.getBlockIdentifiers(), new AssignmentSimple(lValue, new Literal(TypedLiteral.getInt(-1))), swatch.getIndex().justBefore());
        List<Op03SimpleStatement> swatchFrom = swatch.getSources();
        for (Op03SimpleStatement from : swatchFrom) {
            from.replaceTarget(swatch, init);
            init.addSource(from);
        }
        init.addTarget(swatch);
        swatch.getSources().clear();
        swatch.addSource(init);
        in.add(init);
        bytecodeMeta.set(BytecodeMeta.CodeInfoFlag.STRING_SWITCHES);
        return true;
    }

    private static class DistinctSwitchTarget {
        List<OriginalSwitchLookupInfo> entries = ListFactory.newList();
        final int idx;

        private DistinctSwitchTarget(int idx) {
            this.idx = idx;
        }

        void add(OriginalSwitchLookupInfo item) {
            entries.add(item);
        }
    }

    private static class OriginalSwitchLookupInfo {
        Op03SimpleStatement ifTest;
        Op03SimpleStatement stringMatchJump;
        public TypedLiteral literal;
        public Op03SimpleStatement target;

        OriginalSwitchLookupInfo(Op03SimpleStatement ifTest, Op03SimpleStatement stringMatchJump, TypedLiteral literal, Op03SimpleStatement target) {
            this.ifTest = ifTest;
            this.stringMatchJump = stringMatchJump;
            this.literal = literal;
            this.target = target;
        }
    }

    private static class FakeSwitch implements DecodedSwitch {
        private final List<DecodedSwitchEntry> entry;

        private FakeSwitch(List<DecodedSwitchEntry> entry) {
            this.entry = entry;
        }

        @Override
        public List<DecodedSwitchEntry> getJumpTargets() {
            return entry;
        }
    }
}
