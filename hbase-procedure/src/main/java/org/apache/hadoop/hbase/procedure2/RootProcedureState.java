/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.procedure2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

import org.apache.hadoop.hbase.shaded.protobuf.generated.ProcedureProtos.ProcedureState;

/**
 * Internal state of the ProcedureExecutor that describes the state of a "Root Procedure".
 * A "Root Procedure" is a Procedure without parent, each subprocedure will be
 * added to the "Root Procedure" stack (or rollback-stack).
 *
 * RootProcedureState is used and managed only by the ProcedureExecutor.
 *    Long rootProcId = getRootProcedureId(proc);
 *    rollbackStack.get(rootProcId).acquire(proc)
 *    rollbackStack.get(rootProcId).release(proc)
 *    ...
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
class RootProcedureState<TEnvironment> { // 描述root procedure的状态

  private enum State {
    RUNNING,         // The Procedure is running or ready to run
    FAILED,          // The Procedure failed, waiting for the rollback executing
    ROLLINGBACK,     // The Procedure failed and the execution was rolledback
  }

  private Set<Procedure<TEnvironment>> subprocs = null; // 所包含的procedure
  private ArrayList<Procedure<TEnvironment>> subprocStack = null; // procedure顺序，可能重复
  private State state = State.RUNNING;
  private int running = 0;

  public synchronized boolean isFailed() {
    switch (state) {
      case ROLLINGBACK:
      case FAILED:
        return true;
      default:
        break;
    }
    return false;
  }

  public synchronized boolean isRollingback() {
    return state == State.ROLLINGBACK;
  }

  /**
   * Called by the ProcedureExecutor to mark rollback execution
   */
  protected synchronized boolean setRollback() {
    if (running == 0 && state == State.FAILED) {
      state = State.ROLLINGBACK;
      return true;
    }
    return false;
  }

  /**
   * Called by the ProcedureExecutor to mark rollback execution
   */
  protected synchronized void unsetRollback() {
    assert state == State.ROLLINGBACK;
    state = State.FAILED;
  }

  protected synchronized long[] getSubprocedureIds() {
    if (subprocs == null) {
      return null;
    }
    return subprocs.stream().mapToLong(Procedure::getProcId).toArray();
  }

  protected synchronized List<Procedure<TEnvironment>> getSubproceduresStack() {
    return subprocStack;
  }

  protected synchronized RemoteProcedureException getException() {
    if (subprocStack != null) {
      for (Procedure<TEnvironment> proc: subprocStack) {
        if (proc.hasException()) {
          return proc.getException();
        }
      }
    }
    return null;
  }

  /**
   * Called by the ProcedureExecutor to mark the procedure step as running.
   */
  protected synchronized boolean acquire(Procedure<TEnvironment> proc) {
    if (state != State.RUNNING) {
      return false;
    }

    running++;
    return true;
  }

  /**
   * Called by the ProcedureExecutor to mark the procedure step as finished.
   */
  protected synchronized void release(Procedure<TEnvironment> proc) {
    running--;
  }

  protected synchronized void abort() {
    if (state == State.RUNNING) {
      state = State.FAILED;
    }
  }

  /**
   * Called by the ProcedureExecutor after the procedure step is completed,
   * to add the step to the rollback list (or procedure stack)
   */
  protected synchronized void addRollbackStep(Procedure<TEnvironment> proc) {
    if (proc.isFailed()) {
      state = State.FAILED;
    }
    if (subprocStack == null) {
      subprocStack = new ArrayList<>();
    }
    proc.addStackIndex(subprocStack.size());
    subprocStack.add(proc); // sub procedure栈
  }

  protected synchronized void addSubProcedure(Procedure<TEnvironment> proc) {
    if (!proc.hasParent()) {
      return;
    }
    if (subprocs == null) {
      subprocs = new HashSet<>();
    }
    subprocs.add(proc); // sub procedure集合
  }

  /**
   * Called on store load by the ProcedureExecutor to load part of the stack.
   *
   * Each procedure has its own stack-positions. Which means we have to write
   * to the store only the Procedure we executed, and nothing else.
   * on load we recreate the full stack by aggregating each procedure stack-positions.
   */
  protected synchronized void loadStack(Procedure<TEnvironment> proc) {
    // step 1: 添加到sub procedure集合
    addSubProcedure(proc);
    // step 2: 添加到sub procedure栈
    int[] stackIndexes = proc.getStackIndexes();
    if (stackIndexes != null) {
      if (subprocStack == null) {
        subprocStack = new ArrayList<>();
      }
      int diff = (1 + stackIndexes[stackIndexes.length - 1]) - subprocStack.size();
      if (diff > 0) {
        subprocStack.ensureCapacity(1 + stackIndexes[stackIndexes.length - 1]);
        while (diff-- > 0) subprocStack.add(null);
      }
      for (int i = 0; i < stackIndexes.length; ++i) {
        subprocStack.set(stackIndexes[i], proc); // 栈的这几个位置，都是这个procedure
      }
    }
    // step 3: 设置状态
    if (proc.getState() == ProcedureState.ROLLEDBACK) {
      state = State.ROLLINGBACK;
    } else if (proc.isFailed()) {
      state = State.FAILED;
    }
  }

  /**
   * Called on store load by the ProcedureExecutor to validate the procedure stack.
   */
  protected synchronized boolean isValid() {
    if (subprocStack != null) {
      for (Procedure<TEnvironment> proc : subprocStack) {
        if (proc == null) {
          return false;
        }
      }
    }
    return true;
  }
}
