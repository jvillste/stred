# presentation / tutorial topics

- global application state in applicaiton/state-atom


# lenses

## the way it is now
- notebook
  - outline view
    - lens
      - editor
        - value lens
          - outline view

## the way it should be
- notebook
  - outline view
    - lens
      - editor
        - lens map
          - outline view

## definitions as of now
notebook has array of outline views
outline view has a lens, which is an array of editors
editor has attribute and value lens, which is an array of editors


## definitions as they should be
editor should have a lens map, which maps each value to a lens

lens is a sequence of attribute lens map -pairs
lens map is a sequence of value lens pairs

outline view is defined by a value and a lens


lens is a sequence of editors
editor is an attribute lens map or attribute lens pair. Editor can thus have separate lens for each value, or a shared lens.

a value in an editor can be closed or open. When it's closed, it's editors are hidden.



when should a lens be added to a lens map?


## node view

- notebook
  - node view
    - lens
    - table lens
      - entity attribute editor
        - lens map
          - node view


- notebook
    - outline-view
        - lens
        - table-view
            - lens map
            - outline-view
