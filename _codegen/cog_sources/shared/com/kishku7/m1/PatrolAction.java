package com.kishku7.m1;

import com.kishku7.m1.agent.AbstractComposite;
import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

/**
 * Composite goal that visits a sequence of (x, z) waypoints by pushing one {@link MoveAction} per
 * waypoint -- lazily, the next leg only after the previous one finishes.
 *
 * <p>This is the first demonstration of the layered model: a single high-level command expands, one
 * step at a time, into a series of smaller proven actions. It touches the world only through its
 * children; the engine runs each child to completion before this composite is stepped again, at
 * which point it pushes the next leg or reports the patrol complete.
 *
 * <p>(Skeleton failure policy: a leg that fails is popped and the patrol continues to the next
 * waypoint -- per-child failure propagation is an open design item, m1-agent.md section 12.)
 */
public final class PatrolAction extends AbstractComposite {

    private final double[][] points; // each entry is { x, z }
    private int i;

    public PatrolAction(double[][] points) {
        this.points = points.clone();
    }

    @Override
    public String name() {
        return "patrol";
    }

    @Override
    protected StepResult expand(ActionContext ctx) {
        if (i < points.length) {
            double[] pt = points[i];
            i++;
            ctx.report(ReportClass.STATUS,
                    "patrol: leg " + i + "/" + points.length + " -> (" + pt[0] + ", " + pt[1] + ")");
            ctx.push(new MoveAction(pt[0], Double.NaN, pt[1], 1.0, "patrol leg " + i));
            return StepResult.RUNNING;
        }
        ctx.report(ReportClass.STATUS, "patrol: complete (" + points.length + " legs)");
        return StepResult.DONE;
    }
}
