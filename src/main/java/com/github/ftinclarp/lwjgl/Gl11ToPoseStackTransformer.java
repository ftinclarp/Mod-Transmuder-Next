package com.github.ftinclarp.lwjgl;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;

/**
 * AST transformer that rewrites LWJGL 2 (GL11) fixed-function calls to the modern
 * Fabric/PoseStack equivalents so GL11-heavy ported TESR/GUI code runs unchanged on
 * LWJGL 3 without the legacy GL state machine.
 */
public final class Gl11ToPoseStackTransformer {

    public long rewritten() { return rewritten; }
    public long removed() { return removed; }

    private long rewritten;
    private long removed;
    private boolean axisUsed;

    /**
     * Transforms one source file. Rewrites GL11 calls in every method body that
     * references them.
     *
     * <p>The rewritten code references a {@code PoseStack matrices} variable.
     * The ported TESR/GUI render methods do NOT receive one (Forge 1.7.10
     * signature), so the caller must make {@code matrices} available in scope.
     * In the MTN architecture the minecraft-layer base class
     * {@code TileEntitySpecialRenderer} declares a {@code public PoseStack
     * matrices} field (set by the forge-layer {@code ForgeTESRBridge} from the
     * Fabric render call), which the transformed mod code inherits — so no
     * per-mod injection is needed here. For classes outside that hierarchy the
     * caller is responsible for supplying {@code matrices} (e.g. a local, a
     * parameter, or another field).
     */
    public String transform(String source) {
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(source);
        cu.getImports().removeIf(imp -> isLwjglImport(imp));
        axisUsed = false;
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            rewriteIfGl11(call);
        }
        if (axisUsed) {
            cu.addImport("net.minecraft.util.math.RotationAxis");
        }
        return cu.toString();
    }

    private boolean isLwjglImport(ImportDeclaration imp) {
        String n = imp.getNameAsString();
        return n.startsWith("org.lwjgl.opengl.GL11") || n.startsWith("org.lwjgl.opengl.GL12");
    }

    private boolean rewriteIfGl11(MethodCallExpr call) {
        String callee = call.getNameAsString();
        switch (callee) {
            case "glPushMatrix":
                call.replace(StaticJavaParser.parseExpression("matrices.push()"));
                rewritten++;
                return true;
            case "glPopMatrix":
                call.replace(StaticJavaParser.parseExpression("matrices.pop()"));
                rewritten++;
                return true;
            case "glTranslatef":
                if (call.getArguments().size() == 3) {
                    call.replace(StaticJavaParser.parseExpression(
                            "matrices.translate(" + args(call) + ")"));
                    rewritten++;
                    return true;
                }
                return false;
            case "glScalef":
                if (call.getArguments().size() == 3) {
                    call.replace(StaticJavaParser.parseExpression(
                            "matrices.scale(" + args(call) + ")"));
                    rewritten++;
                    return true;
                }
                return false;
            case "glRotatef":
                if (call.getArguments().size() == 4) {
                    String angle = call.getArgument(0).toString().trim();
                    String axis = axisFrom(call);
                    if (axis != null) {
                        call.replace(StaticJavaParser.parseExpression(
                                "matrices.multiply(" + axis + ".rotationDegrees(" + angle + "))"));
                        axisUsed = true;
                        rewritten++;
                        return true;
                    }
                }
                return false;
            case "glColor4f":
            case "glEnable":
            case "glDisable":
                removeStatement(call);
                return true;
            default:
                return false;
        }
    }

    private String args(MethodCallExpr call) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < call.getArguments().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(call.getArguments().get(i));
        }
        return sb.toString();
    }

    private void removeStatement(MethodCallExpr call) {
        Node cur = call;
        while (cur != null) {
            if (cur instanceof ExpressionStmt) {
                removed++;
                cur.remove();
                return;
            }
            cur = cur.getParentNode().orElse(null);
        }
        call.remove();
        removed++;
    }

    private String axisFrom(MethodCallExpr call) {
        double rx = num(call.getArgument(1), 1);
        double ry = num(call.getArgument(2), 1);
        double rz = num(call.getArgument(3), 1);
        if (rx != 0) {
            return rx > 0 ? "RotationAxis.POSITIVE_X" : "RotationAxis.NEGATIVE_X";
        }
        if (ry != 0) {
            return ry > 0 ? "RotationAxis.POSITIVE_Y" : "RotationAxis.NEGATIVE_Y";
        }
        return rz > 0 ? "RotationAxis.POSITIVE_Z" : "RotationAxis.NEGATIVE_Z";
    }

    private double num(Expression e, double fallback) {
        try {
            return Double.parseDouble(e.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
