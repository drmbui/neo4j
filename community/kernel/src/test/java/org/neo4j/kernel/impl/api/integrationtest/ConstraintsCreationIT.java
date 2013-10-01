/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.integrationtest;

import java.util.Iterator;

import javax.transaction.HeuristicRollbackException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAException;

import org.junit.Before;
import org.junit.Test;
import org.neo4j.helpers.Function;
import org.neo4j.kernel.api.DataWriteOperations;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.SchemaWriteOperations;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.DropConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.NoSuchConstraintException;
import org.neo4j.kernel.impl.api.SchemaStorage;
import org.neo4j.kernel.impl.api.index.IndexDescriptor;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.UniquenessConstraintRule;
import org.neo4j.kernel.impl.transaction.TxManager;

import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;
import static org.neo4j.helpers.collection.IteratorUtil.asCollection;
import static org.neo4j.helpers.collection.IteratorUtil.asSet;
import static org.neo4j.helpers.collection.IteratorUtil.emptySetOf;
import static org.neo4j.helpers.collection.IteratorUtil.single;
import static org.neo4j.kernel.api.properties.Property.property;

public class ConstraintsCreationIT extends KernelIntegrationTest
{
    @Test
    public void shouldBeAbleToStoreAndRetrieveUniquenessConstraintRule() throws Exception
    {
        // given
        UniquenessConstraint constraint;
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // when
            constraint = statement.uniquenessConstraintCreate( labelId, propertyKeyId );

            // then
            assertEquals( constraint, single( statement.constraintsGetForLabelAndPropertyKey( labelId,propertyKeyId ) ) );
            assertEquals( constraint, single( statement.constraintsGetForLabel(  labelId ) ) );

            // given
            commit();
        }
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // when
            Iterator<UniquenessConstraint> constraints = statement
                    .constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId );

            // then
            assertEquals( constraint, single( constraints ) );
        }
    }

    @Test
    public void shouldNotPersistUniquenessConstraintsCreatedInAbortedTransaction() throws Exception
    {
        // given
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            statement.uniquenessConstraintCreate( labelId, propertyKeyId );

            // when
            rollback();
        }
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // then
            Iterator<UniquenessConstraint> constraints = statement.constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId );
            assertFalse( "should not have any constraints", constraints.hasNext() );
        }
    }

    @Test
    public void shouldNotStoreUniquenessConstraintThatIsRemovedInTheSameTransaction() throws Exception
    {
        // given
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            UniquenessConstraint constraint = statement.uniquenessConstraintCreate( labelId, propertyKeyId );

            // when
            statement.constraintDrop( constraint );

            // then
            assertFalse( "should not have any constraints", statement.constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId ).hasNext() );

            // when
            commit();
        }
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // then
            assertFalse( "should not have any constraints", statement.constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId ).hasNext() );
        }
    }

    @Test
    public void shouldNotCreateUniquenessConstraintThatAlreadyExists() throws Exception
    {
        // given
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            commit();
        }

        // when
        try
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            statement.uniquenessConstraintCreate( labelId, propertyKeyId );

            fail( "Should not have validated" );
        }
        // then
        catch ( AlreadyConstrainedException e )
        {
            // good
        }
    }

    @Test
    public void shouldNotRemoveConstraintThatGetsReAdded() throws Exception
    {
        // given
        UniquenessConstraint constraint;
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            constraint = statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            commit();
        }
        SchemaStateCheck schemaState = new SchemaStateCheck().setUp();
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // when
            statement.constraintDrop( constraint );
            statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            commit();
        }
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // then
            assertEquals( singletonList( constraint ), asCollection( statement.constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId ) ) );
            schemaState.assertNotCleared();
        }
    }

    @Test
    public void shouldClearSchemaStateWhenConstraintIsCreated() throws Exception
    {
        // given
        SchemaStateCheck schemaState = new SchemaStateCheck().setUp();

        SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

        // when
        statement.uniquenessConstraintCreate( labelId, propertyKeyId );
        commit();

        // then
        schemaWriteOperationsInNewTransaction();
        schemaState.assertCleared();
    }

    @Test
    public void shouldClearSchemaStateWhenConstraintIsDropped() throws Exception
    {
        // given
        UniquenessConstraint constraint;
        SchemaStateCheck schemaState;
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            constraint = statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            commit();

            schemaState = new SchemaStateCheck().setUp();
        }

        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            // when
            statement.constraintDrop( constraint );
            commit();
        }

        // then
        schemaWriteOperationsInNewTransaction();
        schemaState.assertCleared();
    }

    @Test
    public void shouldCreateAnIndexToGoAlongWithAUniquenessConstraint() throws Exception
    {
        // when
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            commit();
        }

        // then
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            assertEquals( asSet( new IndexDescriptor( labelId, propertyKeyId ) ), asSet( statement.uniqueIndexesGetAll() ) );
        }
    }

    @Test
    public void shouldDropCreatedConstraintIndexWhenRollingBackConstraintCreation() throws Exception
    {
        // given
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            assertEquals( asSet( new IndexDescriptor( labelId, propertyKeyId ) ),
                          asSet( statement.uniqueIndexesGetAll() ) );
        }

        // when
        rollback();

        // then
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            assertEquals( emptySetOf( IndexDescriptor.class ), asSet( statement.uniqueIndexesGetAll() ) );
            commit();
        }
    }

    @Test
    public void shouldDropConstraintIndexWhenDroppingConstraint() throws Exception
    {
        // given
        UniquenessConstraint constraint;
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            constraint = statement.uniquenessConstraintCreate( labelId, propertyKeyId );
            assertEquals( asSet( new IndexDescriptor( labelId, propertyKeyId ) ), asSet( statement.uniqueIndexesGetAll() ) );
            commit();
        }

        // when
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            statement.constraintDrop( constraint );
            commit();
        }

        // then
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            assertEquals( emptySetOf( IndexDescriptor.class ), asSet( statement.uniqueIndexesGetAll( ) ) );
            commit();
        }
    }

    @Test
    public void shouldNotDropConstraintThatDoesNotExist() throws Exception
    {
        // when
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();

            try
            {
                statement.constraintDrop( new UniquenessConstraint( labelId, propertyKeyId ) );
                fail( "Should not have dropped constraint" );
            }
            catch ( DropConstraintFailureException e )
            {
                assertThat( e.getCause(), instanceOf( NoSuchConstraintException.class ) );
            }
            commit();
        }

        // then
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            assertEquals( emptySetOf( IndexDescriptor.class ), asSet( statement.uniqueIndexesGetAll() ) );
            commit();
        }
    }

    @Test
    public void committedConstraintRuleShouldCrossReferenceTheCorrespondingIndexRule() throws Exception
    {
        // when
        SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
        statement.uniquenessConstraintCreate( labelId, propertyKeyId );
        commit();

        // then
        SchemaStorage schema = new SchemaStorage( neoStore().getSchemaStore() );
        IndexRule indexRule = schema.indexRule( labelId, propertyKeyId );
        UniquenessConstraintRule constraintRule = schema.uniquenessConstraint( labelId, propertyKeyId );
        assertEquals( constraintRule.getId(), indexRule.getOwningConstraint().longValue() );
        assertEquals( indexRule.getId(), constraintRule.getOwnedIndex() );
    }

    // Note: This test currently depends on circular-dependency components, like the TxManager.
    // Once transactions are properly decoupled, it should just use two kernel transactions, and not worry
    // about XA exception codes.
    @Test
    public void shouldNotAllowOldUncommittedTransactionsToResumeAndViolateConstraint() throws Exception
    {
        // Given
        TxManager txManager = db.getDependencyResolver().resolveDependency( TxManager.class );

        DataWriteOperations s1 = dataWriteOperationsInNewTransaction();
        createNodeWithLabelAndProperty( s1, labelId, propertyKeyId, "Bob" );

        Transaction suspendedTx = txManager.suspend();

        // When
        SchemaWriteOperations schemaStatement = schemaWriteOperationsInNewTransaction();
        schemaStatement.uniquenessConstraintCreate( labelId, propertyKeyId );
        commit();

        DataWriteOperations s3 = dataWriteOperationsInNewTransaction();
        createNodeWithLabelAndProperty( s3, labelId, propertyKeyId, "Bob" );
        commit();

        // Then
        txManager.resume( suspendedTx );
        try
        {
            txManager.commit();
            fail("Expected this commit to fail :(");
        }
        catch( HeuristicRollbackException e )
        {
            XAException cause = (XAException) e.getCause();
            assertThat(cause.errorCode, equalTo(XAException.XA_RBINTEGRITY));
        }
    }

    private void createNodeWithLabelAndProperty( DataWriteOperations statement, int labelId, int propertyKeyId,
                                                 String value ) throws Exception
    {
        long node = statement.nodeCreate();
        statement.nodeAddLabel( node, labelId );
        statement.nodeSetProperty( node, property( propertyKeyId, value ));
    }

    private int labelId, propertyKeyId;

    @Before
    public void createKeys() throws KernelException
    {
        SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
        this.labelId = statement.labelGetOrCreateForName( "Foo" );
        this.propertyKeyId = statement.propertyKeyGetOrCreateForName( "bar" );
        commit();
    }

    private class SchemaStateCheck implements Function<String, Integer>
    {
        int invocationCount;
        private ReadOperations readOperations;

        @Override
        public Integer apply( String s )
        {
            invocationCount++;
            return Integer.parseInt( s );
        }

        public SchemaStateCheck setUp()
        {
            this.readOperations = readOperationsInNewTransaction();
            checkState();
            commit();
            return this;
        }

        public void assertCleared()
        {
            int count = invocationCount;
            checkState();
            assertEquals( "schema state should have been cleared.", count + 1, invocationCount );
        }

        public void assertNotCleared()
        {
            int count = invocationCount;
            checkState();
            assertEquals( "schema state should not have been cleared.", count, invocationCount );
        }

        private SchemaStateCheck checkState()
        {
            assertEquals( Integer.valueOf( 7 ), readOperations.schemaStateGetOrCreate( "7", this ) );
            return this;
        }
    }
}