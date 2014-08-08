package com.tinkerpop.gremlin.process.graph.step.map

import com.tinkerpop.gremlin.process.Traversal
import com.tinkerpop.gremlin.structure.Vertex

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
class GroovyOrderTestImpl extends OrderTest {

    public Traversal<Vertex, String> get_g_V_name_order() {
        g.V.name.order
    }

    public Traversal<Vertex, String> get_g_V_name_orderXabX() {
        g.V.name.order { a, b -> b.instance() <=> a.instance() }
    }

    public Traversal<Vertex, String> get_g_V_orderXa_nameXb_nameX_name() {
        g.V.order { a, b -> a.instance().value('name') <=> b.instance().value('name') }.name
    }
}
