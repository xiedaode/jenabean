package thewebsemantic;

import static thewebsemantic.JenaHelper.asIndividual;
import static thewebsemantic.JenaHelper.asLiteral;
import static thewebsemantic.JenaHelper.date;
import static thewebsemantic.JenaHelper.listAllIndividuals;
import static thewebsemantic.TypeWrapper.get;
import static thewebsemantic.TypeWrapper.type;
import static thewebsemantic.Util.last;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;

import com.hp.hpl.jena.ontology.Individual;
import com.hp.hpl.jena.ontology.OntClass;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.NodeIterator;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.shared.Lock;

/**
 * RDF2Bean converts one or more RDF nodes into java beans. Normally these are
 * nodes created by the Bean2RDF class. <code>@Namespace</code> annotation value of the Class.
 * @author Taylor Cowan
 * @see Bean2RDF
 */
public class RDF2Bean extends Base {
	private HashMap<String, Object> cycle;

	private boolean shallow = false;

	public RDF2Bean(OntModel m) {
		super(m);
	}
	
	public <T> Collection<T> loadDeep(Class<T> c) {
		return load(c, false);
	}

	public <T> Collection<T> load(Class<T> c) {
		return load(c, true);
	}
	
	
	/**
	 * load all rdf entries that map to the bean.
	 * 
	 * @param <T>
	 * @param c
	 * @return
	 */
	public synchronized <T> Collection<T> load(Class<T> c, boolean shallow) {
		m.enterCriticalSection(Lock.READ);
		this.shallow = shallow;
		cycle = new HashMap<String, Object>();
		try {
			return loadAll(c, new LinkedList<T>());
		} finally {
			m.leaveCriticalSection();
		}
	}

	private <T> Collection<T> loadAll(Class<T> c, LinkedList<T> list) {
		for (Individual i : listAllIndividuals(getOntClass(c)))
			list.add(toObject(c, i));
		return list;
	}

	/**
	 * 
	 * @param c
	 *            java class of the bean
	 * @param id
	 *            unique id of the bean to find
	 * @return An instance of T, otherwise null
	 */
	public <T> T loadDeep(Class<T> c, int id) {
		return load(c, Integer.toString(id), false);
	}

	public <T> T loadDeep(Class<T> c, String id) {
		return load(c, id, false);
	}

	public <T> T load(Class<T> c, String id) {
		return load(c, id, true);
	}

	public <T> T load(Class<T> c, int id) {
		return load(c, Integer.toString(id), true);
	}

	public synchronized <T> T load(Class<T> c, String id, boolean shallow) {
		m.enterCriticalSection(Lock.READ);
		this.shallow = shallow;
		cycle = new HashMap<String, Object>();
		try {
			return toObject(c, id);
		} finally {
			m.leaveCriticalSection();
		}
	}

	public Filler fill(Object o) {
		return new Filler(this, o);
	}

	public synchronized void fill(Object o, String propertyName) {
		m.enterCriticalSection(Lock.READ);
		this.shallow = true;
		try {
			applyProperty(o, propertyName);
		} finally {
			m.leaveCriticalSection();
		}
	}

	public boolean exists(Class<?> c, String id) {
		return !(m.getIndividual(get(c).uri(id)) == null);
	}

	private <T> T toObject(Class<T> c, String id) {
		if (get(c).uriSupport())
			return toObject(c, m.getIndividual(id));
		else
			return toObject(c, m.getIndividual(get(c).uri(id)));
	}

	private <T> T toObject(Class<T> c, Individual i) {
		try {
			if (i != null)
				return (isCycle(i)) ? (T) cycle.get(i.getURI())
						: (T) applyProperties(i);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private <T> T toObject(Class<T> c, RDFNode node) {
		return (node.isLiteral()) ? (T) asLiteral(node).getValue() : toObject(
				c, asIndividual(node));
	}

	private Object toObject(PropertyDescriptor p, Individual i) {
		return toObject(p.getPropertyType(), i);
	}

	private boolean isCycle(Individual i) {
		return cycle.containsKey(i.getURI());
	}

	private Object applyProperty(Object target, String propertyName) {
		// cycle.put(source.getURI(), target);
		String uri = TypeWrapper.instanceURI(target);
		Individual source = m.getIndividual(uri);
		for (PropertyDescriptor p : type(target).descriptors()) {
			if (p.getName().equals(propertyName) && 
					p.getPropertyType().equals(Collection.class))
					apply(source, new PropertyContext(target, p));
		}
		return target;
	}

	private Object applyProperties(Individual source) {
		Object target = newInstance(source);
		cycle.put(source.getURI(), target);
		for (PropertyDescriptor p : type(target).descriptors())
			if (!(shallow && p.getPropertyType().equals(Collection.class)))
				apply(source, new PropertyContext(target, p));
		return target;
	}

	private Object newInstance(Individual source) {
		Object o = null;
		try {
			Class<?> c = getJavaclass(source);
			TypeWrapper type = TypeWrapper.get(c);
			try {
				Constructor<?> m = c.getConstructor(String.class);
				if (type.uriSupport())
					o = m.newInstance(source.getURI());
				else
					o = m.newInstance(last(source.getURI()));
			} catch (NoSuchMethodException e) {
				// this is expected, we'll use default constructor
			}
			if (o == null)
				o = c.newInstance();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return o;
	}

	/**
	 * Given an Individual, return the appropriate class. This could have been
	 * stored as an annotation if jenabean was involved in writing the
	 * individual. If not we'll use the binder.
	 * 
	 * @param source
	 * @return
	 * @throws ClassNotFoundException
	 */
	private Class<?> getJavaclass(Individual source)
			throws ClassNotFoundException {
		OntClass oc = (OntClass) source.getRDFType().as(OntClass.class);
		RDFNode node = oc.getPropertyValue(javaclass);
		if (node != null) {
			Literal className = (Literal) node.as(Literal.class);
			return Class.forName(className.getString());
		} else { // try the binder
			Resource type = source.getRDFType();
			return binder.getClass(type.getURI());
		}
	}

	private OntClass getOntClass(Class<?> c) {
		if (binder.isBound(c))
			return m.getOntClass(binder.getUri(c));
		else
			return m.getOntClass(TypeWrapper.get(c).typeUri());
	}

	/**
	 * Apply a particular property of an Individual (rdf) to a Java Object
	 * 
	 * @param i
	 *            Found individual we are using as a data source
	 * @param o
	 *            raw object ready to receive data from rdf
	 * @param property
	 *            descriptor for property we are applying
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 */
	private void apply(Individual i, PropertyContext ctx) {
		Property p = m.getOntProperty(ctx.uri());
		if (p != null)
			apply(ctx, i.listPropertyValues(p));
	}

	private void apply(PropertyContext ctx, NodeIterator nodes) {
		if (nodes == null || !nodes.hasNext())
			return;
		else if (ctx.isCollection())
			collection(ctx, nodes);
		else if (ctx.isPrimitive())
			applyLiteral(ctx, asLiteral(nodes.nextNode()));
		else
			applyIndividual(ctx, asIndividual(nodes.nextNode()));
	}

	private void collection(PropertyContext ctx, NodeIterator nodes) {
		ctx.invoke(fillCollection(t(ctx.property), nodes.toSet()));
	}

	private ArrayList<Object> fillCollection(Class<?> c, Set<RDFNode> nodes) {
		ArrayList<Object> results = new ArrayList<Object>();
		for (RDFNode node : nodes)
			results.add(toObject(c, node));
		return results;
	}

	private void applyIndividual(PropertyContext ctx, Individual i) {
		ctx.invoke(toObject(ctx.property, i));
	}

	private void applyLiteral(PropertyContext ctx, Literal l) {
		if (ctx.isDate())
			ctx.invoke(date(l));
		else
			ctx.invoke(l.getValue());
	}
}
/*
 * Copyright (c) 2007 Taylor Cowan
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */