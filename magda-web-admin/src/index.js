// @flow
// eslint-disable-next-line
import'es6-shim';

import createLogger from "redux-logger";
import "./index.css";
import {
  BrowserRouter,
  Switch, Route
} from "react-router-dom";
import thunkMiddleware from "redux-thunk";
import React from "react";
import ReactDOM from "react-dom";
import AppContainer from "./Components/AppContainer";

import Account from "./Components/Account/Account";
import Connectors from "./Components/Connectors/Connectors";
import ConnectorConfig from "./Components/Connectors/ConnectorConfig";
import SelectDataset from "./Components/Connectors/SelectDataset";

import { Provider } from "react-redux";
import reducer from "./reducers/reducer";
import { createStore, applyMiddleware } from "redux";
import { requestWhoAmI } from "./actions/userManagementActions";





// eslint-disable-next-line
const loggerMiddleware = createLogger();

const store: Store = createStore(
  reducer,
  applyMiddleware(
    thunkMiddleware, // lets us dispatch() functions
    loggerMiddleware // neat middleware that logs actions
  )
);

function loadDefaultData(store) {
  store.dispatch(requestWhoAmI());
}

const Main = ()=>(
  <main>
    <Switch>
          <Route exact path='/' component={AppContainer} onEnter={loadDefaultData(store)}/>
          <Route path="/account" component={Account}/>
          <Switch>


            <Route path="/connectors/:connectorId/:datasetId" component={ConnectorConfig} />
            <Route path="/connectors/:connectorId" component={SelectDataset}/>
            <Route exact path="/connectors" component={Connectors}/>
          </Switch>
    </Switch>
  </main>
)

const App = ()=>(
  <div><Main/></div>
)

ReactDOM.render(
  <Provider store={store}>
    <BrowserRouter basename='/admin'>
      <App/>
    </BrowserRouter>
  </Provider>,
  document.getElementById("root")
);