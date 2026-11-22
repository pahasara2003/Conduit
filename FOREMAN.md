# Foreman Worker Rules & Conventions

## Active Herdr Agent Panes & Topology

| Agent / Role | Kind | Pane ID | Tab ID | Workspace | Communication Command |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **planner** | agy (planner) | `w3:p1` | `w3:t1` | `w3` | `herdr agent prompt w3:p1 "<message>"` or `foreman send planner "<message>"` |
| **worker-claude** | claude | `w3:p2` | `w3:t2` | `w3` | `herdr agent prompt w3:p2 "<message>"` or `foreman send worker-claude "<message>"` |
| **worker-gemini** | agy | `w3:p3` | `w3:t3` | `w3` | `herdr agent prompt w3:p3 "<message>"` or `foreman send worker-gemini "<message>"` |

> [!NOTE]
> **Direct Inter-Agent Relay:** All active agents and their assigned Herdr Pane IDs are listed above.
> To send a task, clarification, or handoff to any agent, target their **Pane ID** or **Foreman Name** directly.
> Do not run discovery searches for active agent panes.